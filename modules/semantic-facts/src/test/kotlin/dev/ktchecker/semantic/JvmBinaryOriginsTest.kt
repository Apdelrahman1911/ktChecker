package dev.ktchecker.semantic

import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

/** Synthetic DTO/format controls ONLY: no FIR objects, compiler process, VFS or artifact reads. */
class JvmBinaryOriginsTest {
    private val bytes = "fun f() = 1\n".toByteArray()
    private val digest = Digests.sha256("synthetic origin contract, not extraction evidence")
    private val source = SemanticSource("src/A.kt", Digests.sha256(bytes), bytes.size.toLong(), "main")
    private val artifact = ArtifactIdentity("synthetic-only", digest,
        Path.of("synthetic-origin-fixture.jar").toAbsolutePath().normalize().toString())
    private val context = CompilerSourceContext("synthetic-source-module", "JVM", false, emptyList())
    private val binarySymbol = identity(SymbolFact("", "function", "synthetic.library.f", "synthetic-binary-module",
        "Library", false, returnType = TypeFact("class", "kotlin.Int", false)))
    private val container = JvmBinaryContainerObservation("jar", "/synthetic/library.jar!/synthetic/LibraryKt.class",
        "/synthetic/library.jar", "synthetic/LibraryKt.class", "synthetic/LibraryKt", 16,
        Digests.sha256("synthetic bytes; deliberately not a classfile"))

    private fun identity(value: SymbolFact): SymbolFact = value.copy(id = resolvedSymbolIdentity(value))
    private fun request(observe: Boolean = false) = SemanticFactsRequest(digest, digest, digest,
        listOf(artifact), artifact, Path.of(".").toAbsolutePath().normalize().toString(), listOf(source),
        listOf(SemanticSourceSet("main")), "main", "jvm", "synthetic-contract", listOf("-language-version", "2.4"),
        observeJvmBinaryOrigins = observe)
    private fun observed(symbol: SymbolFact = binarySymbol) =
        JvmBinaryOriginObservation(symbol.id, "observed", "kotlin_package_part", container)
    private fun unknown(symbol: SymbolFact = binarySymbol) =
        unknownJvmBinaryOrigin(symbol.id, "kotlin_container_source_unavailable")
    private fun shard(input: SemanticFactsRequest, symbols: List<SymbolFact> = listOf(binarySymbol),
                      survey: JvmBinaryOriginSurvey? = null) =
        SemanticFactShard(input.digest(), input.snapshotId, input.analysisId, input.toolchainId,
            input.pluginArtifact.digest, SEMANTIC_COMPILER_VERSION, source, context, emptyList(), symbols,
            emptyList(), emptyList(), emptyList(), emptyList(), jvmBinaryOrigins = survey)
    private fun assemble(input: SemanticFactsRequest, value: SemanticFactShard) =
        SemanticFactsValidation.assemble(input, listOf(value), 0, sourceBytes = { bytes.copyOf() })
    private fun assertProblems(value: JvmBinaryOriginSurvey, reason: String,
                               symbols: List<SymbolFact> = listOf(binarySymbol)) {
        assertEquals(listOf(reason), JvmBinaryOriginValidation.problems(true, value, symbols))
    }

    @Test fun `default request retains old ordered serialization and request digest for identical fields`() {
        val input = request()
        val oldWire = """{"snapshotId":"$digest","analysisId":"$digest","toolchainId":"$digest","compilerArtifacts":[${ContractJson.encodeToString(artifact)}],""" +
            """"pluginArtifact":${ContractJson.encodeToString(artifact)},"sourceRoot":${ContractJson.encodeToString(input.sourceRoot)},"sources":[${ContractJson.encodeToString(source)}],""" +
            """"sourceSets":[{"id":"main","dependsOn":[]}],"leafSourceSet":"main","target":"jvm","compilationId":"synthetic-contract","compilerArguments":["-language-version","2.4"],""" +
            """"dependencyArtifacts":[],"compilerVersion":"2.4.21","adapterId":"kotlin-fir-2.4.21/v1","schemaVersion":1}"""
        assertEquals(oldWire, ContractJson.encodeToString(input))
        assertEquals(Digests.sha256("ktchecker:semantic-request:v1\u0000" +
            Digests.canonical(ContractJson.parseToJsonElement(oldWire))), input.digest())
        val restored = ContractJson.decodeFromString<SemanticFactsRequest>(oldWire)
        assertFalse(restored.observeJvmBinaryOrigins)
        assertEquals(oldWire, ContractJson.encodeToString(restored))
    }

    @Test fun `default shard retains old ordered serialization and absent survey on roundtrip`() {
        val input = request()
        val value = shard(input, emptyList())
        val oldWire = """{"requestDigest":"${input.digest()}","snapshotId":"$digest","analysisId":"$digest","toolchainId":"$digest","pluginDigest":"$digest",""" +
            """"observedCompilerVersion":"2.4.21","input":${ContractJson.encodeToString(source)},"compilerContext":${ContractJson.encodeToString(context)},""" +
            """"declarations":[],"symbols":[],"calls":[],"types":[],"expectActual":[],"unknown":[],"adapterId":"kotlin-fir-2.4.21/v1","schemaVersion":1}"""
        assertEquals(oldWire, ContractJson.encodeToString(value))
        val restored = ContractJson.decodeFromString<SemanticFactShard>(oldWire)
        assertNull(restored.jvmBinaryOrigins)
        assertEquals(oldWire, ContractJson.encodeToString(restored))
    }

    @Test fun `opt in changes request binding but adds no field to original symbol identities`() {
        val old = request()
        val opted = old.copy(observeJvmBinaryOrigins = true)
        assertNotEquals(old.digest(), opted.digest())
        assertEquals(JsonPrimitive(true), ContractJson.parseToJsonElement(ContractJson.encodeToString(opted))
            .jsonObject["observeJvmBinaryOrigins"])
        val oldSymbolWire = ContractJson.encodeToString(binarySymbol)
        val value = shard(opted, survey = JvmBinaryOriginSurvey(listOf(observed())))
        val decoded = ContractJson.decodeFromString<SemanticFactShard>(ContractJson.encodeToString(value))
        assertEquals(value, decoded)
        assertEquals(oldSymbolWire, ContractJson.encodeToString(decoded.symbols.single()))
        assertEquals(binarySymbol.id, resolvedSymbolIdentity(decoded.symbols.single()))
        assertFalse("jvmBinaryOrigins" in oldSymbolWire)
    }

    @Test fun `opt in requires survey and old request rejects an unsolicited survey`() {
        val opted = request(true)
        val missing = assemble(opted, shard(opted))
        assertEquals("failed", missing.state)
        assertContains(missing.reasons, "semantic_jvm_binary_origin_survey_missing")
        val old = request()
        val unsolicited = assemble(old, shard(old, survey = JvmBinaryOriginSurvey(listOf(unknown()))))
        assertEquals("failed", unsolicited.state)
        assertContains(unsolicited.reasons, "semantic_jvm_binary_origin_not_requested")
        assertTrue(missing.shards.isEmpty() && unsolicited.shards.isEmpty())
    }

    @Test fun `empty survey is allowed only for an actually empty structural symbol domain`() {
        val empty = JvmBinaryOriginSurvey(emptyList())
        assertTrue(JvmBinaryOriginValidation.problems(true, empty, emptyList()).isEmpty())
        assertProblems(empty, "semantic_jvm_binary_origin_symbol_binding_invalid")
    }

    @Test fun `unknown observations stay unknown even when original vocabulary is complete`() {
        val input = request(true)
        val row = unknown()
        val bundle = assemble(input, shard(input, survey = JvmBinaryOriginSurvey(listOf(row))))
        assertTrue(bundle.completeForSupportedVocabulary)
        assertEquals("extracted", bundle.state)
        assertEquals(row, bundle.shards.single().jvmBinaryOrigins!!.observations.single())
        assertNull(row.container)
        assertFalse(bundle.tupleQualified)
        assertFalse(bundle.customRulesQualified)
    }

    @Test fun `synthetic observed DTO passes only structural validation and never qualification`() {
        val input = request(true)
        val bundle = assemble(input, shard(input, survey = JvmBinaryOriginSurvey(listOf(observed()))))
        // The byte digest above intentionally describes non-class bytes; this boundary does no
        // artifact IO and cannot authenticate a caller-created observation, even if well shaped.
        assertTrue(bundle.completeForSupportedVocabulary)
        assertEquals(observed(), bundle.shards.single().jvmBinaryOrigins!!.observations.single())
        assertFalse(bundle.tupleQualified)
        assertFalse(bundle.customRulesQualified)
    }

    @Test fun `unknown observer and schema versions cannot borrow current survey interpretation`() {
        val survey = JvmBinaryOriginSurvey(listOf(unknown()))
        listOf(survey.copy(observerId = "foreign"), survey.copy(schemaVersion = 2)).forEach {
            assertProblems(it, "semantic_jvm_binary_origin_observer_unsupported")
        }
    }

    @Test fun `survey joins every original symbol ID exactly once without extras or omissions`() {
        val row = unknown()
        listOf(emptyList(), listOf(row, row), listOf(row.copy(symbolId = Digests.sha256("foreign"))),
            listOf(row, row.copy(symbolId = Digests.sha256("extra")))).forEach {
            assertProblems(JvmBinaryOriginSurvey(it), "semantic_jvm_binary_origin_symbol_binding_invalid")
        }
    }

    @Test fun `unknown vocabulary rejects contradictory coordinates routes and invented states`() {
        val row = unknown()
        listOf(row.copy(reason = null), row.copy(reason = "invented_reason"), row.copy(route = "kotlin_package_part"),
            row.copy(container = container), row.copy(state = "verified")).forEach {
            assertProblems(JvmBinaryOriginSurvey(listOf(it)), "semantic_jvm_binary_origin_observation_invalid")
        }
    }

    @Test fun `observed row requires supported nonlocal library function and consistent route origin`() {
        val span = SourceCoordinates(source, bytes).span(0, 0, 0, 3)
        listOf(binarySymbol.copy(kind = "property"), binarySymbol.copy(local = true), binarySymbol.copy(source = span),
            binarySymbol.copy(origin = "Source"), binarySymbol.copy(origin = "Enhancement")).map(::identity).forEach {
            assertProblems(JvmBinaryOriginSurvey(listOf(observed(it))), "semantic_jvm_binary_origin_observation_invalid", listOf(it))
        }
        listOf(observed().copy(route = "java_method_class"), observed().copy(route = "guessed"),
            observed().copy(reason = "kotlin_container_source_unavailable"), observed().copy(container = null)).forEach {
            assertProblems(JvmBinaryOriginSurvey(listOf(it)), "semantic_jvm_binary_origin_observation_invalid")
        }
        val java = identity(binarySymbol.copy(origin = "Java(Library)"))
        val jrt = JvmBinaryContainerObservation("jrt", "/synthetic/jdk!//modules/java.base/java/lang/Thread.class",
            "/synthetic/jdk", "java/lang/Thread.class", "java/lang/Thread", 16, container.classByteDigest, "java.base")
        assertTrue(JvmBinaryOriginValidation.problems(true,
            JvmBinaryOriginSurvey(listOf(observed(java).copy(route = "java_method_class", container = jrt))), listOf(java)).isEmpty())
    }

    @Test fun `observed coordinates sizes and digests must fit the closed DTO shape`() {
        listOf(container.copy(protocol = "file"), container.copy(containerPath = "/different/library.jar"),
            container.copy(entryPath = "different/LibraryKt.class"), container.copy(jdkModule = "forged"),
            container.copy(vfsPath = "file:///synthetic/library.jar!/synthetic/LibraryKt.class"),
            container.copy(binaryClassId = ""), container.copy(binaryClassId = "bad\uD800"),
            container.copy(classByteSize = 7), container.copy(classByteSize = JVM_ORIGIN_MAX_CLASS_BYTES.toLong() + 1),
            container.copy(classByteDigest = "not-a-digest")).forEach {
            assertProblems(JvmBinaryOriginSurvey(listOf(observed().copy(container = it))), "semantic_jvm_binary_origin_observation_invalid")
        }
    }

    @Test fun `physical decoder preserves actual container and entry without class name conventions`() {
        assertEquals(JvmBinaryLocation("/cache/not-a-coordinate.data", "META-INF/versions/21/other/Actual.class", null),
            jvmBinaryLocation("jar", "/cache/not-a-coordinate.data!/META-INF/versions/21/other/Actual.class"))
        assertEquals(JvmBinaryLocation("C:/cache/library.jar", "pkg/A.class", null),
            jvmBinaryLocation("jar", "C:/cache/library.jar!/pkg/A.class"))
        listOf("/jdk!/modules/java.base/java/lang/Thread.class", "/jdk!//modules/java.base/java/lang/Thread.class").forEach {
            assertEquals(JvmBinaryLocation("/jdk", "java/lang/Thread.class", "java.base"), jvmBinaryLocation("jrt", it))
        }
    }

    @Test fun `closed physical decoder rejects traversal URLs lexical aliases and unsupported VFS shapes`() {
        listOf("relative.jar!/pkg/A.class", "file:///tmp/a.jar!/pkg/A.class", "/tmp//a.jar!/pkg/A.class",
            "/tmp/../a.jar!/pkg/A.class", "//server/a.jar!/pkg/A.class", "/tmp/a.jar!//pkg/A.class",
            "/tmp/a.jar!/pkg/./A.class", "/tmp/a.jar!/pkg/../A.class", "/tmp/a.jar!/pkg//A.class",
            "/tmp/a.jar!/pkg/A.kt", "/tmp/a.jar!/inner.jar!/pkg/A.class", "C:/tmp/a:stream.jar!/pkg/A.class",
            "/tmp/a.jar!/C:/pkg/A.class", "/tmp/a.jar!/pkg\\A.class", "/tmp/a.jar!/pkg/A\u007f.class").forEach {
            assertNull(jvmBinaryLocation("jar", it), "Unexpected supported shape: $it")
        }
        listOf("/jdk!/java.base/java/lang/Thread.class", "/jdk!///modules/java.base/java/lang/Thread.class",
            "/jdk!/modules/../java/lang/Thread.class", "/jdk!/modules/java.base/../Thread.class").forEach {
            assertNull(jvmBinaryLocation("jrt", it))
        }
        listOf("file", "zip", "http", "mock", "jar://").forEach {
            assertNull(jvmBinaryLocation(it, "/tmp/a.jar!/pkg/A.class"))
        }
    }

    @Test fun `origin text keeps well formed Unicode but rejects controls and broken UTF16`() {
        assertTrue(boundedOriginText("/é🙂/A.class"))
        listOf("", "x".repeat(4_097), "a\u0000b", "a\r\nb", "a\u0085b", "a\\b",
            "a\uD800", "a\uDC00", "a\uD800b").forEach { assertFalse(boundedOriginText(it)) }
        assertNull(jvmBinaryLocation("jar", "/tmp/\uD800.jar!/pkg/A.class"))
    }

    @Test fun `structural collisions never choose an observed origin by arrival order`() {
        val first = observed()
        val second = first.copy(container = container.copy(vfsPath = "/other/library.jar!/synthetic/LibraryKt.class",
            containerPath = "/other/library.jar"))
        assertEquals(listOf(first), jvmBinaryOriginSurvey(listOf(first, first)).observations)
        val expected = unknownJvmBinaryOrigin(first.symbolId, "ambiguous_structural_symbol_origin")
        listOf(listOf(first, second), listOf(second, first), listOf(first, unknown()),
            listOf(unknown(), first)).forEach { assertEquals(listOf(expected), jvmBinaryOriginSurvey(it).observations) }
    }

    @Test fun `survey collections detach before source callbacks and cannot mutate validated output`() {
        val input = request(true)
        val rows = mutableListOf(unknown())
        val shards = mutableListOf(shard(input, survey = JvmBinaryOriginSurvey(rows)))
        val bundle = SemanticFactsValidation.assemble(input, shards, 0, sourceBytes = {
            rows.clear()
            shards.clear()
            bytes.copyOf()
        })
        assertTrue(bundle.completeForSupportedVocabulary)
        val frozen = bundle.shards.single().jvmBinaryOrigins!!.observations
        assertEquals(listOf(unknown()), frozen)
        assertFailsWith<UnsupportedOperationException> { (frozen as MutableList<*>).clear() }
    }

    @Test fun `per survey record header and field limits fail before semantic interpretation`() {
        val ordinary = JvmBinaryOriginSurvey(listOf(unknown()))
        listOf(ordinary.copy(observerId = "x".repeat(257)), ordinary.copy(observations = List(20_001) { unknown() }),
            ordinary.copy(observations = listOf(unknown().copy(reason = "x".repeat(4_097))))).forEach {
            assertEquals("semantic_jvm_binary_origin_shape_limit", assertFailsWith<SemanticFactException> {
                JvmBinaryOriginValidation.freeze(it, JvmBinaryOriginFreezeBudget())
            }.code)
        }
    }

    @Test fun `aggregate string budget is shared across the whole shard vector before callbacks`() {
        val input = request(true)
        val rows = JvmBinaryOriginSurvey(List(120) { unknown().copy(reason = "x".repeat(4_096)) })
        var reads = 0
        val bundle = SemanticFactsValidation.assemble(input, List(3) { shard(input, survey = rows) }, 0,
            sourceBytes = { reads++; bytes.copyOf() })
        assertEquals("failed", bundle.state)
        assertEquals(listOf("semantic_jvm_binary_origin_shape_limit"), bundle.reasons)
        assertEquals(0, reads)
    }

    @Test fun `aggregate record budget is not reset by each small malformed shard`() {
        val input = request(true)
        // Minimal malformed text isolates the record bound from the independent character bound.
        val rows = JvmBinaryOriginSurvey(List(20_000) { JvmBinaryOriginObservation("", "") })
        assertEquals("semantic_jvm_binary_origin_shape_limit", assertFailsWith<SemanticFactException> {
            SemanticFactsFreeze.shards(List(6) { shard(input, emptyList(), rows) }, 6)
        }.code)
    }

    @Test fun `existing fixture opt in is explicit boolean JVM only with absent default unchanged`() {
        assertFalse(semanticFixtureJvmOriginsRequested("jvm", null))
        assertFalse(semanticFixtureJvmOriginsRequested("jvm", "false"))
        assertFalse(semanticFixtureJvmOriginsRequested("native", null))
        assertTrue(semanticFixtureJvmOriginsRequested("jvm", "true"))
        assertFailsWith<IllegalArgumentException> { semanticFixtureJvmOriginsRequested("native", "true") }
        listOf("", "TRUE", "yes", "1", " false ").forEach { value ->
            assertFailsWith<IllegalArgumentException> { semanticFixtureJvmOriginsRequested("jvm", value) }
        }
    }

    @Test fun `metadata projection never serializes private origin coordinates class IDs or digests`() {
        val input = request(true)
        val privateContainer = container.copy(vfsPath = "/PRIVATE_OWNER/container.jar!/PRIVATE_CLASS.class",
            containerPath = "/PRIVATE_OWNER/container.jar", entryPath = "PRIVATE_CLASS.class", binaryClassId = "PRIVATE_CLASS")
        val bundle = assemble(input, shard(input, survey = JvmBinaryOriginSurvey(listOf(observed().copy(container = privateContainer)))))
        val report = SemanticFixtureRunner.SuiteEvidence(state = "passed", mode = "jvm", target = "jvm",
            observedAt = "2026-11-05T00:00:00Z", testedCommit = null, runner = emptyMap(), artifacts = emptyList(),
            cases = listOf(SemanticFixtureRunner.CaseEvidence("synthetic-origin-control", true, emptyMap(), bundle)),
            executions = emptyList(), reasons = emptyList())
        val summary = SemanticFixtureSummary.from(report, digest)
        val wire = ContractJson.encodeToString(summary)
        listOf("PRIVATE_OWNER", "PRIVATE_CLASS", "jvmBinaryOrigins", "vfsPath", "containerPath", "entryPath",
            "binaryClassId", "classByteDigest", "jdkModule", privateContainer.classByteDigest).forEach {
            assertFalse(it in wire, "Private observation escaped metadata projection")
        }
        assertFalse(summary.tupleQualified)
        assertFalse(summary.customRulesQualified)
    }
}

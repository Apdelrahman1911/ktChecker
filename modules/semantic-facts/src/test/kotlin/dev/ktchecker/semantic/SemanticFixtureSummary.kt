package dev.ktchecker.semantic

import dev.ktchecker.contracts.AnalysisMetrics
import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.Serializable

/**
 * An explicit CI allowlist projection. Never serialize a source/fact object, command, raw output,
 * artifact.path, DEVELOPER_DIR, or arbitrary environment variable into an uploadable report.
 * Logical artifact coordinates may contain portable distribution-relative component names.
 */
@Serializable
internal data class SemanticFixtureSummary(
    val schemaVersion: Int = 1,
    val evidenceKind: String = "executed_semantic_fact_fixtures",
    val metadataProjection: String = "metadata-only-v1",
    val adapterId: String = SEMANTIC_ADAPTER_ID,
    val state: String,
    val mode: String,
    val target: String,
    val observedAt: String,
    val testedCommit: String?,
    val runner: Map<String, String>,
    val artifacts: List<SemanticArtifactSummary>,
    val cases: List<SemanticCaseSummary>,
    val executions: List<SemanticExecutionSummary>,
    val reasons: List<String>,
    val privateEvidenceDigest: String,
    val tupleQualified: Boolean = false,
    val customRulesQualified: Boolean = false,
    val limitations: List<String> = SEMANTIC_LIMITATIONS + listOf(
        "Synthetic feasibility assertions are not corpus, diagnostic precision, platform, or release qualification.",
        "The tested commit does not prove the absence of uncommitted changes; artifact and snapshot digests bind actual inputs.",
        "No compiler commands, source paths, symbol names, diagnostic text, raw streams, or machine-local artifact paths are published.",
        "Private evidence must be retained locally if authorized; its digest is only a binding, not publicly inspectable execution evidence.",
        "Native library compilation does not prove linking, simulator execution, cinterop, or target runtime correctness.",
    ),
) {
    companion object {
        fun from(evidence: SemanticFixtureRunner.SuiteEvidence, privateDigest: String): SemanticFixtureSummary =
            SemanticFixtureSummary(state = choice(evidence.state, setOf("passed", "failed", "unavailable")),
                mode = choice(evidence.mode, setOf("jvm", "native")),
                target = choice(evidence.target, setOf("jvm", "linuxX64", "iosArm64", "iosSimulatorArm64")),
                observedAt = evidence.observedAt.takeIf { runCatching { java.time.Instant.parse(it) }.isSuccess } ?: "unavailable",
                testedCommit = evidence.testedCommit?.takeIf { it.matches(Regex("[0-9a-f]{40,64}")) },
                runner = evidence.runner.filter { (key, value) -> key in RUNNER_KEYS && SAFE_RUNNER_VALUE.matches(value) },
                artifacts = evidence.artifacts.map(::artifact).distinct().sortedWith(compareBy({ it.coordinate }, { it.digest })),
                cases = evidence.cases.map { row ->
                    val bundle = row.bundle
                    val shards = bundle.shards
                    SemanticCaseSummary(code(row.name), row.passed, row.assertions.mapKeys { code(it.key) },
                        digest(bundle.snapshotId), digest(bundle.requestDigest), digest(bundle.analysisId),
                        choice(bundle.state, setOf("extracted", "incomplete", "failed", "unavailable")),
                        bundle.compilerExitCode, bundle.reasons.map(::code).distinct().sorted(), bundle.completeForSupportedVocabulary,
                        shards.map { code(it.observedCompilerVersion) }.distinct().sorted(),
                        mapOf("visitedSourceInputs" to shards.map { it.input.path }.distinct().size,
                            "shards" to shards.size, "declarations" to shards.sumOf { it.declarations.size },
                            "symbolFacts" to shards.sumOf { it.symbols.size },
                            "uniqueSymbolIds" to shards.flatMap { it.symbols }.map { it.id }.distinct().size,
                            "calls" to shards.sumOf { it.calls.size }, "typeUses" to shards.sumOf { it.types.size },
                            "expectActualCandidates" to shards.sumOf { it.expectActual.size },
                            "unknownFacts" to shards.sumOf { it.unknown.size }),
                        shards.flatMap { it.unknown }.groupingBy { code(it.reason) }.eachCount().toSortedMap())
                }, executions = evidence.executions.map { row ->
                    SemanticExecutionSummary(code(row.label), digest(row.snapshotId), digest(row.analysisKey), code(row.status),
                        row.exitCode, row.reasons.map(::code).distinct().sorted(), row.metrics,
                        digest(row.stdoutDigest), digest(row.stderrDigest))
                }, reasons = evidence.reasons.map(::code).distinct().sorted(), privateEvidenceDigest = digest(privateDigest))

        private fun artifact(value: ArtifactIdentity): SemanticArtifactSummary {
            val coordinate = value.coordinate
            val safe = coordinate == "ktchecker:$SEMANTIC_ADAPTER_ID" ||
                coordinate.matches(Regex("selected-semantic-jdk:(bin/java(?:\\.exe)?|release|lib/modules)")) ||
                coordinate.matches(Regex("selected-kotlin-compiler:[A-Za-z0-9_.+-]{1,200}\\.jar")) ||
                coordinate.matches(Regex("fixture-dependency:(jar|klib):(jvm|linuxX64|iosArm64|iosSimulatorArm64)")) ||
                (coordinate.startsWith("selected-native-2.4.21:") &&
                    coordinate.substringAfter(':').let { component ->
                        component.startsWith("bin/") || component.startsWith("konan/") || component.startsWith("klib/")
                    } && coordinate.substringAfter(':').split('/').all { it.matches(Regex("[A-Za-z0-9_.+-]{1,200}")) && it !in setOf(".", "..") })
            return SemanticArtifactSummary(if (safe) coordinate else "unrecognized_coordinate_omitted", digest(value.digest),
                if (safe) null else Digests.sha256(coordinate))
        }

        private val RUNNER_KEYS = setOf("os", "architecture", "fixtureHostJava", "ImageOS", "ImageVersion",
            "RUNNER_OS", "RUNNER_ARCH", "GITHUB_SHA", "GITHUB_RUN_ID", "projectJavaVersion", "projectJavaRuntimeVersion", "projectJavaVendor")
        private val SAFE_RUNNER_VALUE = Regex("[A-Za-z0-9._+() -]{1,160}")
        private fun choice(value: String, allowed: Set<String>): String = value.takeIf { it in allowed } ?: "unavailable"
        private fun code(value: String): String = value.takeIf { it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.:-]{0,159}")) }
            ?: "unstructured_detail_omitted"
        private fun digest(value: String): String = value.takeIf(Digests::valid) ?: "invalid_digest"
    }
}

@Serializable
internal data class SemanticArtifactSummary(val coordinate: String, val digest: String, val omittedCoordinateDigest: String? = null)

@Serializable
internal data class SemanticCaseSummary(
    val name: String,
    val passed: Boolean,
    val assertions: Map<String, Boolean>,
    val snapshotId: String,
    val requestDigest: String,
    val analysisId: String,
    val state: String,
    val compilerExitCode: Int?,
    val reasons: List<String>,
    val completeForSupportedVocabulary: Boolean,
    val observedCompilerVersions: List<String>,
    val counts: Map<String, Int>,
    val unknownReasonCounts: Map<String, Int>,
)

@Serializable
internal data class SemanticExecutionSummary(
    val label: String,
    val snapshotId: String,
    val analysisKey: String,
    val status: String,
    val exitCode: Int?,
    val reasons: List<String>,
    val metrics: AnalysisMetrics,
    val stdoutDigest: String,
    val stderrDigest: String,
)

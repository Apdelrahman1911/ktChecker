package dev.ktchecker.providers.lint

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlin.test.*

/** Actual isolated maintained Lint execution, limited to the explicit XML detector controls. */
class RealLintFixtureTest {
    @Test fun `actual lint32_3_1 diagnoses positive and accepts legal and provider suppression controls`() {
        val repository = Path.of(requireNotNull(System.getProperty("ktchecker.repository")))
        val fixture = repository.resolve("providers/platform-fixtures/android")
        val before = Files.walk(fixture).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().associateWith(::fileDigest) }
        for (case in listOf("positive", "negative", "suppression")) {
            val resultPath = Files.createTempDirectory("ktchecker-real-lint-").resolve("$case.json")
            LintFixtureMain.main(arrayOf("--fixture", fixture.toString(), "--case", case, "--result", resultPath.toString(),
                "--lint-classpath", requireNotNull(System.getProperty("ktchecker.lint.classpath"))))
            val envelope = ContractJson.decodeFromString<FixtureCheckEnvelope>(Files.readString(resultPath))
            assertEquals("passed", envelope.state, envelope.providerResult.reasons.toString())
            assertEquals("android", envelope.target)
            assertTrue(Digests.valid(envelope.snapshotId))
            assertEquals("32.3.1", envelope.providerResult.providerVersion)
            assertEquals("incomplete", envelope.providerResult.status)
            val binding = assertNotNull(envelope.modelBinding)
            assertEquals(RuleContext("android", "main", ":fixture", "xml"), binding.scope)
            assertTrue(Digests.valid(binding.modelDigest))
            assertEquals(setOf("main"), binding.sourceSetByFile.values.toSet())
            assertTrue(binding.requiredTaskPaths.isEmpty())
            val diagnosed = envelope.providerResult.findings.mapNotNull { it.upstreamId }.toSet()
            assertEquals(if (case == "positive") setOf("ContentDescription", "HardcodedText") else emptySet(), diagnosed)
            assertTrue(envelope.providerResult.coverage.all { it.provenance == "executed" && it.certificateId != null &&
                !it.successful() && !it.tested && !it.qualified })
            assertTrue(envelope.providerResult.rawDiagnostics.isEmpty())
            envelope.providerResult.findings.forEach { finding ->
                assertEquals("res/layout/example.xml", finding.location.path)
                assertTrue(Digests.valid(finding.location.fileDigest))
                assertEquals(binding.scope, finding.context)
                assertTrue(finding.location.line > 1)
                assertEquals("main", binding.sourceSetByFile[finding.location.path])
                val ownership = finding.evidence.single { it.kind == "compilation_source_membership" && it.artifactRef == finding.location.path }
                assertEquals(binding.modelDigest, ownership.digest)
                assertContains(ownership.summary, "Imported source set main;")
            }
        }
        assertEquals(before, before.keys.associateWith(::fileDigest), "Original producer fixture bytes stay read-only")
    }

    @Test fun `prerequisite failures produce structured unavailable output not an absent report`() {
        val work = Files.createTempDirectory("ktchecker-lint-prerequisite-")
        val resultPath = work.resolve("result.json")
        LintFixtureMain.main(arrayOf("--fixture", work.resolve("missing").toString(), "--case", "positive",
            "--result", resultPath.toString(), "--lint-classpath", "unused"))
        val envelope = ContractJson.decodeFromString<FixtureCheckEnvelope>(Files.readString(resultPath))
        assertEquals("unavailable", envelope.state)
        assertEquals("", envelope.snapshotId)
        assertTrue(envelope.providerResult.coverage.isEmpty())
        assertFalse(envelope.providerResult.complete())
    }
}

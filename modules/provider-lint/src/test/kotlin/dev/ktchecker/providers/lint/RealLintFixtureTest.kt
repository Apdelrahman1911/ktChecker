package dev.ktchecker.providers.lint

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual isolated maintained Lint execution, limited to the explicit XML detector controls. */
class RealLintFixtureTest {
    @Test fun `actual lint32_3_1 diagnoses positive and accepts legal and provider suppression controls`() {
        val repository = Path.of(requireNotNull(System.getProperty("ktchecker.repository")))
        val fixture = repository.resolve("providers/platform-fixtures/android")
        val before = Files.walk(fixture).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().associateWith(::fileDigest) }
        for (case in listOf("positive", "negative", "suppression")) {
            // Canonicalize our owner-created temp parent explicitly (e.g. macOS /var aliases).
            val work = Files.createTempDirectory("ktchecker-real-lint-").toRealPath()
            val resultPath = work.resolve("$case.json")
            val privateAttempt = work.resolve("private-attempt")
            val privateIoUnavailable = if (case != "positive") null else try {
                LintFixturePrivateAttempt.requireSupportedParent(work)
                null
            } catch (failure: ExecutionProblem) {
                assertContains(setOf("lint_private_attempt_permissions_unavailable", "lint_private_attempt_secure_io_unavailable"), failure.code)
                failure.code
            }
            val retainPositive = case == "positive" && privateIoUnavailable == null
            LintFixtureMain.main((listOf("--fixture", fixture.toString(), "--case", case, "--result", resultPath.toString(),
                "--lint-classpath", requireNotNull(System.getProperty("ktchecker.lint.classpath"))) +
                if (retainPositive) listOf("--private-attempt-directory", privateAttempt.toString()) else emptyList()).toTypedArray())
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
            if (retainPositive) {
                val metadata = ContractJson.parseToJsonElement(Files.readString(privateAttempt.resolve("attempt.json"))).jsonObject
                assertEquals(envelope.snapshotId, metadata.getValue("driverSnapshotId").jsonPrimitive.content)
                assertTrue(metadata.getValue("process").jsonObject.getValue("successfulExecution").jsonPrimitive.boolean)
                for (stream in listOf("stdout", "stderr")) {
                    assertEquals(fileDigest(privateAttempt.resolve("$stream.log")), metadata.getValue(stream + "Digest").jsonPrimitive.content)
                }
                assertFalse(Files.readString(resultPath).contains("private-attempt"), "No private process path or contents added to the public envelope")
            } else {
                assertFalse(Files.exists(privateAttempt, NOFOLLOW_LINKS), "No raw retention without explicit opt-in")
                if (case == "positive") {
                    // Windows/other unsupported hosts still execute ALL original real Lint
                    // controls above. Separately exercise explicit refusal; do not silently skip.
                    val beforeRefusal = Files.newDirectoryStream(work).use { it.map { path -> path.fileName.toString() }.toSet() }
                    val failure = assertFailsWith<ExecutionProblem> {
                        LintFixturePrivateAttempt.retain(privateAttempt, fixture, envelope.snapshotId, null, null)
                    }
                    assertEquals(privateIoUnavailable, failure.code)
                    assertFalse(Files.exists(privateAttempt, NOFOLLOW_LINKS))
                    assertEquals(beforeRefusal, Files.newDirectoryStream(work).use { it.map { path -> path.fileName.toString() }.toSet() })
                }
            }
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

    @Test fun `private retention refusal cannot erase the original public envelope`() {
        val work = Files.createTempDirectory("ktchecker-lint-private-refusal-").toRealPath()
        val resultPath = work.resolve("result.json")
        val privateAttempt = work.resolve("not-created/../private-attempt")
        val failure = assertFailsWith<ExecutionProblem> {
            LintFixtureMain.main(arrayOf("--fixture", work.resolve("missing").toString(), "--case", "positive",
                "--result", resultPath.toString(), "--lint-classpath", "unused",
                "--private-attempt-directory", privateAttempt.toString()))
        }
        assertEquals("lint_private_attempt_destination_alias", failure.code)
        val envelopeText = Files.readString(resultPath)
        val envelope = ContractJson.decodeFromString<FixtureCheckEnvelope>(envelopeText)
        assertEquals("unavailable", envelope.state)
        assertEquals("", envelope.snapshotId)
        assertTrue(envelope.providerResult.coverage.isEmpty())
        assertFalse(envelope.providerResult.complete())
        assertFalse(envelopeText.contains("private-attempt"))
        assertFalse(Files.exists(work.resolve("private-attempt"), NOFOLLOW_LINKS))
        assertFalse(Files.exists(work.resolve("not-created"), NOFOLLOW_LINKS))
    }

    @Test fun `invalid private path text is not printed or appended to the public envelope`() {
        val work = Files.createTempDirectory("ktchecker-lint-invalid-private-").toRealPath()
        val resultPath = work.resolve("result.json")
        val failure = assertFailsWith<ExecutionProblem> {
            LintFixtureMain.main(arrayOf("--fixture", work.resolve("missing").toString(), "--case", "positive",
                "--result", resultPath.toString(), "--lint-classpath", "unused",
                "--private-attempt-directory", "PRIVATE\u0000invalid"))
        }
        assertEquals("lint_private_attempt_write_failed", failure.code)
        assertFalse(failure.message.orEmpty().contains("PRIVATE"))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        val envelopeText = Files.readString(resultPath)
        assertEquals("unavailable", ContractJson.decodeFromString<FixtureCheckEnvelope>(envelopeText).state)
        assertFalse(envelopeText.contains("PRIVATE"))
        assertEquals(setOf("result.json"), Files.newDirectoryStream(work).use { it.map { path -> path.fileName.toString() }.toSet() })
    }
}

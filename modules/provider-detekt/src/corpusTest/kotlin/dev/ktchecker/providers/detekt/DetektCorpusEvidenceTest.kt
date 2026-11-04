package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Filesystem authoring controls only: no provider, native helper, or process execution. */
class DetektCorpusEvidenceTest {
    @TempDir lateinit var temporary: Path
    private fun repository(): Path = Files.createDirectory(temporary.resolve("repository"))
    private fun sidecar(cohort: String = DetektCorpusCohort.NAME) = buildJsonObject {
        put("schema_version", 1); put("provider_id", "detekt"); put("provider_version", DETEKT_VERSION)
        put("context", buildJsonObject { put("corpus_cohort", cohort) }); put("rules", JsonArray(emptyList()))
    }

    @Test fun `redirect must remain in build without traversal`() {
        val root = repository()
        assertEquals(root.resolve("providers/detekt-fixtures"), DetektCorpusEvidence.selectedRoot(root, null))
        assertEquals(root.resolve("build/evidence/corpus/detekt"), DetektCorpusEvidence.selectedRoot(root, "build/evidence/corpus"))
        for (selected in listOf("providers/detekt-fixtures", temporary.resolve("outside").toString(), "build/../build/evidence"))
            assertFailsWith<IllegalArgumentException> { DetektCorpusEvidence.selectedRoot(root, selected) }
    }

    @Test fun `fresh anchored events cannot overwrite or append to an earlier event`() {
        val root = repository()
        DetektCorpusEvidence(root, "build/evidence/corpus").use { evidence ->
            val bytes = "original event\n".toByteArray()
            val reference = evidence.artifact("progress/0001.json", bytes)
            val path = root.resolve(reference["path"]!!.jsonPrimitive.content)
            assertContentEquals(bytes, Files.readAllBytes(path))
            assertEquals(Digests.sha256(bytes).removePrefix("sha256:"), reference["sha256"]!!.jsonPrimitive.content)
            assertFails { evidence.artifact("progress/0001.json", "replacement".toByteArray()) }
            assertContentEquals(bytes, Files.readAllBytes(path))
            evidence.verifyDirectories()
        }
    }

    @Test fun `artifact traversal absolute and empty paths do not escape owned run`() {
        val root = repository()
        DetektCorpusEvidence(root, "build/evidence/corpus").use { evidence ->
            for (relative in listOf("../escape.json", "/escape.json", "unsafe\\path.json", "a/../escape.json", ""))
                assertFails { evidence.artifact(relative, "blocked".toByteArray()) }
            assertFalse(Files.exists(root.resolve("escape.json")))
            assertFails { evidence.artifact("empty.json", byteArrayOf()) }
            assertFalse(Files.exists(evidence.runDirectory.resolve("empty.json")))
        }
    }

    @Test fun `symlink ancestor is rejected before evidence directory creation`() {
        val root = repository()
        val outside = Files.createDirectory(temporary.resolve("outside"))
        Files.createDirectories(root.resolve("build"))
        Files.createSymbolicLink(root.resolve("build/linked"), outside)
        assertFails { DetektCorpusEvidence(root, "build/linked/corpus") }
        assertFalse(Files.exists(outside.resolve("corpus")))
        Files.createSymbolicLink(root.resolve("providers"), outside)
        assertFails { DetektCorpusEvidence(root, null) }
        assertFalse(Files.exists(outside.resolve("detekt-fixtures")))
    }

    @Test fun `symlink artifact parent and replaced run identity prevent subsequent writes`() {
        val root = repository()
        val outside = Files.createDirectory(temporary.resolve("outside"))
        DetektCorpusEvidence(root, "build/evidence/corpus").use { evidence ->
            evidence.artifact("kept.json", "retained".toByteArray())
            Files.createSymbolicLink(evidence.runDirectory.resolve("linked"), outside)
            assertFails { evidence.artifact("linked/escape.json", "blocked".toByteArray()) }
            assertFalse(Files.exists(outside.resolve("escape.json")))
            val original = evidence.runDirectory.resolveSibling("preserved-original")
            Files.move(evidence.runDirectory, original)
            Files.createDirectory(evidence.runDirectory)
            assertFails { evidence.artifact("new.json", "blocked".toByteArray()) }
            assertFalse(Files.exists(evidence.runDirectory.resolve("new.json")))
            assertEquals("retained", Files.readString(original.resolve("kept.json")))
        }
    }

    @Test fun `cohort withdrawal preserves original bytes and never touches ordinary sidecars`() {
        val root = repository()
        DetektCorpusEvidence(root, null).use { evidence ->
            val ordinary = evidence.root.resolve("evidence/verified-jvm-syntax-native-preview1.json")
            Files.writeString(ordinary, "ordinary original")
            val original = sidecar()
            evidence.publish(original)
            val originalBytes = Files.readAllBytes(evidence.root.resolve(evidence.liveRelative))
            evidence.prepareCohort()
            assertContentEquals(originalBytes, Files.readAllBytes(evidence.runDirectory.resolve("prior-live-sidecar.json")))
            assertFalse(Files.exists(evidence.root.resolve(evidence.liveRelative)))
            assertEquals("ordinary original", Files.readString(ordinary))
            evidence.publish(sidecar())
            evidence.withdrawCohort()
            assertFalse(Files.exists(evidence.root.resolve(evidence.liveRelative)))
            assertEquals("ordinary original", Files.readString(ordinary))
        }
    }

    @Test fun `live cleanup refuses symlink wrong cohort and replacement file identities`() {
        val root = repository()
        val original = temporary.resolve("outside-original.json")
        Files.writeString(original, sidecar().toString())
        DetektCorpusEvidence(root, "build/evidence/corpus").use { evidence ->
            val live = evidence.root.resolve(evidence.liveRelative)
            Files.createSymbolicLink(live, original)
            assertFails { evidence.withdrawCohort() }
            assertTrue(Files.isSymbolicLink(live))
            Files.delete(live)
            Files.writeString(live, sidecar("unapproved-other-cohort").toString())
            assertFails { evidence.prepareCohort() }
            assertTrue(Files.exists(live))
            Files.delete(live)
            evidence.publish(sidecar())
            Files.move(live, live.resolveSibling("preserved-published.json"))
            Files.writeString(live, sidecar().toString())
            assertFails { evidence.withdrawCohort() }
            assertTrue(Files.exists(live))
            assertEquals(sidecar().toString(), Files.readString(original))
        }
    }

    @Test fun `hard linked live sidecar is not an owned cleanup target`() {
        val root = repository()
        val original = temporary.resolve("outside-original.json")
        Files.writeString(original, sidecar().toString())
        DetektCorpusEvidence(root, "build/evidence/corpus").use { evidence ->
            val live = evidence.root.resolve(evidence.liveRelative)
            Files.createLink(live, original)
            assertFails { evidence.withdrawCohort() }
            assertTrue(Files.exists(live))
            assertEquals(sidecar().toString(), Files.readString(original))
            assertFails { evidence.publish(sidecar()) }
            assertEquals(sidecar().toString(), Files.readString(original))
        }
    }

    @Test fun `private scratch and every job ancestor remain after evidence close`() {
        val root = repository()
        val evidence = DetektCorpusEvidence(root, "build/evidence/corpus")
        val scratch = evidence.createRetainedScratch()
        Files.writeString(scratch.resolve("retained-synthetic-job.json"), "not a real process")
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(scratch)))
        assertFails { evidence.createRetainedScratch() }
        evidence.close()
        assertTrue(Files.isDirectory(scratch))
        assertTrue(Files.isDirectory(evidence.runDirectory))
        assertTrue(Files.isDirectory(evidence.root))
        assertEquals("not a real process", Files.readString(scratch.resolve("retained-synthetic-job.json")))
    }

    @Test fun `late deadline after publication rolls back only live leaving explicit candidates`() {
        val root = repository()
        DetektCorpusEvidence(root, "build/evidence/corpus").use { evidence ->
            val candidate = JsonObject(sidecar() + mapOf("candidate_only" to JsonPrimitive(true), "run_complete" to JsonPrimitive(false)))
            evidence.artifact("candidate-sidecar.json", candidate.toString().toByteArray())
            val scratch = evidence.createRetainedScratch()
            var calls = 0
            assertFailsWith<IllegalStateException> { evidence.publish(candidate) {
                if (++calls == 2) error("synthetic aggregate deadline after expensive commit")
            } }
            assertEquals(2, calls)
            assertFalse(Files.exists(evidence.root.resolve(evidence.liveRelative)))
            val retained = Json.parseToJsonElement(Files.readString(evidence.runDirectory.resolve("candidate-sidecar.json"))).jsonObject
            assertTrue(retained["candidate_only"]!!.jsonPrimitive.boolean)
            assertFalse(retained["run_complete"]!!.jsonPrimitive.boolean)
            assertTrue(Files.isDirectory(scratch))
            assertTrue(Files.isDirectory(evidence.runDirectory))
        }
    }
}

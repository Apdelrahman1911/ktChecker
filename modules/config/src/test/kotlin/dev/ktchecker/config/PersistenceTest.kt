package dev.ktchecker.config

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PersistenceTest {
    @Test fun separateStoreInstancesCasBothLayersAndReopenTheCommittedRevision(): Unit = ConfigFixture().use { fixture ->
        val other = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, fixture.authority, clock = fixture.clock)
        val initial = fixture.store.capture(fixture.binding)
        assertEquals(initial.revisionToken, other.capture(fixture.binding).revisionToken)
        val first = fixture.request(ConfigAction.SetRuleEnabled(RULE, false), expected = initial.revisionToken)
        val second = fixture.request(ConfigAction.SetRuleEnabled(OTHER_RULE, false), ConfigLayer.LOCAL, initial.revisionToken)
        val current = fixture.store.mutate(first, fixture.authority.issueForExplicitOwnerAction(OWNER, first, fixture.clock.instant()))
        assertFailsWith<ConfigConflictException> { other.mutate(second, fixture.authority.issueForExplicitOwnerAction(OWNER, second, fixture.clock.instant())) }
        val reopened = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, clock = fixture.clock).capture(fixture.binding)
        assertEquals(current.revisionToken, reopened.revisionToken)
        assertEquals(1L, reopened.shared.revision.revision)
        assertEquals(0L, reopened.local.revision.revision)
        assertEquals(OWNER, reopened.shared.auditEvents.single().actor)
    }

    @Test fun manyConcurrentStaleEditsAcceptExactlyOneAndCannotHalfCommitLayers(): Unit = ConfigFixture().use { fixture ->
        val initial = fixture.store.capture(fixture.binding)
        val stores = List(8) { ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, fixture.authority, clock = fixture.clock) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val attempts = (0 until 32).map { index -> pool.submit<String> {
                start.await()
                val request = fixture.request(ConfigAction.AddSuppression(SuppressionSpec("edit-$index", PolicySelector.Rule(RULE), SuppressionScope.Project,
                    SuppressionReason.ACCEPTED_RISK, "Concurrent explicit owner edit")),
                    if (index % 2 == 0) ConfigLayer.SHARED else ConfigLayer.LOCAL, initial.revisionToken)
                val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())
                try { stores[index % stores.size].mutate(request, permit); "applied" } catch (_: ConfigConflictException) { "stale" }
            } }
            start.countDown()
            val outcomes = attempts.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it == "applied" })
            assertEquals(31, outcomes.count { it == "stale" })
            val reopened = fixture.store.capture(fixture.binding)
            assertEquals(1L, reopened.shared.revision.revision + reopened.local.revision.revision)
            assertEquals(1, reopened.shared.suppressions.size + reopened.local.suppressions.size)
        } finally { pool.shutdownNow() }
    }

    @Test fun commonProjectLockWorksAcrossIndependentJvmProcesses(): Unit = ConfigFixture().use { fixture ->
        val barrier = fixture.root.resolve("barrier")
        Files.createDirectory(barrier)
        val classpath = listOf(
            ConfigProcessWorker::class.java, ConfigStore::class.java, dev.ktchecker.contracts.Finding::class.java,
            kotlinx.serialization.json.Json::class.java, kotlinx.serialization.Serializable::class.java,
            kotlin.Unit::class.java,
        ).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val children = (0..1).map { index -> ProcessBuilder(java, "-Xmx96m", "-cp", classpath,
            ConfigProcessWorker::class.java.name, fixture.root.resolve("state").toString(), barrier.toString(), index.toString())
            .redirectErrorStream(true).start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while ((0..1).any { !Files.exists(barrier.resolve("ready-$it")) }) {
                if (System.nanoTime() > deadline) error("Child config processes did not reach the barrier.")
                if (children.any { !it.isAlive }) error("Child config process exited before barrier: " + children.filter { !it.isAlive }.joinToString { it.inputStream.bufferedReader().readText() })
                Thread.sleep(10)
            }
            Files.createFile(barrier.resolve("go"))
            val outcomes = children.map { child ->
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Config child must finish")
                val output = child.inputStream.bufferedReader().readText().trim()
                assertEquals(0, child.exitValue(), output)
                output
            }
            assertEquals(1, outcomes.count { it == "APPLIED" })
            assertEquals(1, outcomes.count { it == "STALE" })
            assertEquals(1L, fixture.store.capture(fixture.binding).shared.revision.revision)
        } finally { children.filter { it.isAlive }.forEach { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) } }
    }

    @Test fun sharedPolicyIsProjectWideButLocalPolicyNeverLeaksAcrossOwnersWorktreesOrLineages(): Unit = ConfigFixture().use { fixture ->
        fixture.mutate(ConfigAction.SetRuleEnabled(RULE, false))
        val original = fixture.mutate(ConfigAction.SetRuleEnabled(RULE, true), ConfigLayer.LOCAL)
        val otherTree = fixture.store.capture(fixture.binding.copy(worktreeId = "other-worktree"))
        val otherLineage = fixture.store.capture(fixture.binding.copy(lineageId = "other-lineage"))
        val otherOwner = fixture.store.capture(fixture.binding.copy(ownerId = "other-owner"))
        val otherProject = fixture.store.capture(fixture.binding.copy(workspaceId = "other-project"))
        assertEquals(true, original.ruleSettings.getValue(RULE).enabled)
        for (snapshot in listOf(otherTree, otherLineage)) {
            assertEquals(false, snapshot.ruleSettings.getValue(RULE).enabled)
            assertEquals(original.shared.revision, snapshot.shared.revision)
            assertEquals(0L, snapshot.local.revision.revision)
        }
        assertTrue(otherOwner.ruleSettings.isEmpty())
        assertTrue(otherProject.ruleSettings.isEmpty())
    }

    @Test fun crashBeforeReplaceLeavesOldPolicyAndCrashAfterReplaceIsReportedUncertain(): Unit = ConfigFixture().use { fixture ->
        val old = fixture.suppress()
        val paths = fixture.store.storagePaths(fixture.binding)
        val originalBytes = Files.readAllBytes(paths.shared)
        val beforeFailure = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, fixture.authority, clock = fixture.clock,
            writeObserver = object : ConfigWriteObserver {
                override fun beforeAtomicReplace(temporary: Path, destination: Path) { throw IOException("Simulated interruption before replace") }
            })
        val request = fixture.request(ConfigAction.RemoveSuppression("risk"), expected = old.revisionToken)
        val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())
        val failure = assertFailsWith<ConfigWriteException> { beforeFailure.mutate(request, permit) }
        assertFalse(failure.possiblyApplied)
        assertTrue(originalBytes.contentEquals(Files.readAllBytes(paths.shared)))
        assertEquals(old.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
        val afterFailure = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, fixture.authority, clock = fixture.clock,
            writeObserver = object : ConfigWriteObserver {
                override fun afterAtomicReplace(destination: Path) { throw IOException("Simulated interruption before directory sync") }
            })
        assertTrue(assertFailsWith<ConfigWriteException> { afterFailure.mutate(request, permit) }.possiblyApplied)
        val reopened = fixture.store.capture(fixture.binding)
        assertEquals(2L, reopened.shared.revision.revision)
        assertTrue(reopened.shared.suppressions.isEmpty())
        assertFailsWith<ConfigConflictException> { fixture.store.mutate(request, permit) }
        Files.list(paths.shared.parent).use { files -> assertTrue(files.noneMatch { it.fileName.toString().endsWith(".tmp") }) }
    }

    @Test fun malformedOrUnsupportedConfigurationIsUnavailableNotEmpty(): Unit = ConfigFixture().use { fixture ->
        fixture.suppress()
        val paths = fixture.store.storagePaths(fixture.binding)
        val original = Files.readString(paths.shared)
        for (bad in listOf("{", "", original.replace("\"schemaVersion\":1", "\"schemaVersion\":99"))) {
            Files.writeString(paths.shared, bad)
            assertFailsWith<ConfigReadException> { fixture.store.capture(fixture.binding) }
        }
    }

    @Test fun symlinkedConfigurationIsUnavailableNotEmpty(): Unit = ConfigFixture().use { fixture ->
        fixture.suppress()
        val paths = fixture.store.storagePaths(fixture.binding)
        val original = Files.readString(paths.shared)
        Files.delete(paths.shared)
        val outside = fixture.root.resolve("untrusted.json")
        Files.writeString(outside, original)
        try { Files.createSymbolicLink(paths.shared, outside) }
        catch (_: UnsupportedOperationException) {
            assumeTrue(false, "Filesystem symbolic-link creation unavailable; no configuration alias-control execution coverage is claimed")
        } catch (error: FileSystemException) {
            if (System.getProperty("os.name").startsWith("Windows") && error.reason?.contains("privilege", ignoreCase = true) == true) {
                assumeTrue(false, "Windows symbolic-link privilege unavailable; no configuration alias-control execution coverage is claimed")
            } else throw error
        }
        assertFailsWith<ConfigReadException> { fixture.store.capture(fixture.binding) }
    }

    @Test fun fullTokenRejectsSameRevisionByteEditsAndGenerationAba(): Unit = ConfigFixture().use { fixture ->
        val old = fixture.suppress()
        val paths = fixture.store.storagePaths(fixture.binding)
        val original = Files.readString(paths.shared)
        Files.writeString(paths.shared, original + "\n")
        val byteChanged = fixture.store.capture(fixture.binding)
        assertEquals(old.shared.revision.revision, byteChanged.shared.revision.revision)
        assertNotEquals(old.shared.revision.documentDigest, byteChanged.shared.revision.documentDigest)
        fixture.store.withCurrentConfig(fixture.binding, old.revisionToken) { assertFalse(it.matches) }
        Files.writeString(paths.shared, original.replace(old.shared.revision.generationId!!, UUID.randomUUID().toString()))
        val generationChanged = fixture.store.capture(fixture.binding)
        assertNotEquals(old.shared.revision.generationId, generationChanged.shared.revision.generationId)
        fixture.store.withCurrentConfig(fixture.binding, old.revisionToken) { assertFalse(it.matches) }
    }

    @Test fun recoveredActiveReviewUsesExactArchivedPolicyAndNeverLatestPolicy(): Unit = ConfigFixture().use { fixture ->
        val old = fixture.suppress()
        val finding = fixture.finding()
        val oldInput = fixture.input(old, finding)
        val frozen = ContractJson.decodeFromString<FrozenConfig>(ContractJson.encodeToString(fixture.store.exportSnapshot(old)))
        val current = fixture.mutate(ConfigAction.RemoveSuppression("risk"))
        val reopened = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, clock = fixture.clock)
        val restored = reopened.restoreSnapshot(frozen, old.revisionToken)
        assertEquals(old.revisionToken, restored.revisionToken)
        assertEquals(PolicyDisposition.ACCEPTED, reopened.explain(restored, oldInput, finding).disposition)
        assertEquals(PolicyDisposition.OPEN, reopened.explain(current, fixture.input(current, finding), finding).disposition)
        reopened.withCurrentConfig(fixture.binding, restored.revisionToken) { assertFalse(it.matches) }
        assertFailsWith<ConfigReadException> { reopened.restoreSnapshot(frozen, current.revisionToken) }
        assertFailsWith<ConfigReadException> { reopened.restoreSnapshot(frozen.copy(sharedDocument = frozen.sharedDocument!!.replace("Explicit owner decision", "Forged policy")), old.revisionToken) }
        assertFailsWith<ConfigReadException> { reopened.restoreSnapshot(frozen.copy(sharedDocument = null), old.revisionToken) }
        val incompatible = ConfigStore(fixture.root.resolve("state"), RuleAliases.identities(listOf(RULE)), clock = fixture.clock)
        assertFailsWith<ConfigReadException> { incompatible.restoreSnapshot(frozen, old.revisionToken) }
    }

    @Test fun inspectExplainAndApplyNeverModifySourceOrConfiguration(): Unit = ConfigFixture().use { fixture ->
        val snapshot = fixture.suppress()
        val paths = fixture.store.storagePaths(fixture.binding)
        val configBytes = Files.readAllBytes(paths.shared)
        Files.createDirectories(fixture.root.resolve("source"))
        val source = fixture.root.resolve("source/Example.kt")
        val code = "fun example() = 1"
        Files.writeString(source, code)
        val finding = fixture.finding()
        repeat(5) {
            val captured = fixture.store.inspect(fixture.binding)
            fixture.store.explain(captured, fixture.input(captured, finding), finding)
            fixture.store.apply(captured, fixture.input(captured, finding), dev.ktchecker.contracts.ProviderResult("fixture", "complete", listOf(finding)))
        }
        assertEquals(code, Files.readString(source))
        assertTrue(configBytes.contentEquals(Files.readAllBytes(paths.shared)))
        assertEquals(snapshot.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
    }
}

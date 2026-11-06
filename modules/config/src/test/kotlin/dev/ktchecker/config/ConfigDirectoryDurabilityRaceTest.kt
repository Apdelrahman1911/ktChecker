package dev.ktchecker.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real directory syncs on the test filesystem, not a simulated power-loss guarantee. */
class ConfigDirectoryDurabilityRaceTest {
    @Test fun independentPeerSyncsVisibleDirectoryAndItsParentBeforeCommitAndExactReplayConflicts() {
        for (layer in ConfigLayer.entries) for (nested in listOf(false, true)) withPausedCreator(nested) { fixture, creator ->
            val syncs = mutableListOf<Path>()
            var syncsBeforeReplace = emptyList<Path>()
            val beforeReplacements = mutableListOf<Path>()
            val afterReplacements = mutableListOf<Path>()
            val peer = ConfigStore(creator.stateRoot, TEST_ALIASES, fixture.authority, clock = fixture.clock,
                writeObserver = object : ConfigWriteObserver {
                    override fun beforeDirectorySync(directory: Path) {
                        syncs.add(directory)
                        if (nested && directory == creator.unconfirmedParent) {
                            assertFalse(Files.exists(creator.stateRoot, NOFOLLOW_LINKS),
                                "The observed ancestor link must be synced before creating its missing descendants")
                        }
                    }
                    override fun beforeAtomicReplace(temporary: Path, destination: Path) {
                        creator.assertPaused()
                        syncsBeforeReplace = syncs.toList()
                        beforeReplacements.add(destination)
                    }
                    override fun afterAtomicReplace(destination: Path) { afterReplacements.add(destination) }
                })
            // This token comes from the independent peer, not the fixture's unrelated default store.
            val initial = peer.capture(fixture.binding)
            val request = ConfigMutationRequest(fixture.binding, layer, initial.revisionToken,
                ConfigAction.SetRuleEnabled(RULE, false), UUID.randomUUID().toString())
            val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())
            val committed = peer.mutate(request, permit)
            creator.assertPaused()

            val paths = peer.storagePaths(fixture.binding)
            val target = if (layer == ConfigLayer.SHARED) paths.shared else paths.local
            val opposite = if (layer == ConfigLayer.SHARED) paths.local else paths.shared
            assertEquals(listOf(target), beforeReplacements)
            assertEquals(listOf(target), afterReplacements)
            val directoryIndex = syncsBeforeReplace.indexOf(creator.directory)
            val parentIndex = syncsBeforeReplace.indexOf(creator.unconfirmedParent)
            assertTrue(directoryIndex >= 0, "The peer must sync the directory while its creator is paused")
            assertTrue(parentIndex > directoryIndex,
                "The peer must sync the formerly-unconfirmed parent link, not only the policy directory/file")
            // Hooks record attempts. The peer's successful return also requires the native syncs
            // to complete; neither observation demonstrates behavior under actual power loss.
            val changed = inspection(committed, layer)
            val unchanged = inspection(committed, oppositeLayer(layer))
            assertEquals(1L, changed.revision.revision)
            assertEquals(listOf(RuleSetting(RULE, false)), changed.ruleSettings)
            val event = ConfigAuditEvent(1L, request.requestId, request.digest(), OWNER, fixture.clock.millis(), null)
            assertEquals(listOf(event), changed.auditEvents)
            assertEquals(inspection(initial, oppositeLayer(layer)), unchanged)
            assertEquals(0L, unchanged.revision.revision)
            assertTrue(unchanged.auditEvents.isEmpty())
            assertFalse(Files.exists(opposite, NOFOLLOW_LINKS))
            val committedBytes = Files.readAllBytes(target)

            val original = creator.releaseAndGet()
            val conflict = assertFailsWith<ConfigConflictException> { original.mutate(request, permit) }
            assertEquals(committed.revisionToken, conflict.current)
            val reopened = original.capture(fixture.binding)
            assertEquals(committed.revisionToken, reopened.revisionToken)
            assertEquals(listOf(event), inspection(reopened, layer).auditEvents)
            assertEquals(changed, inspection(reopened, layer))
            assertEquals(unchanged, inspection(reopened, oppositeLayer(layer)))
            assertContentEquals(committedBytes, Files.readAllBytes(target))
            assertFalse(Files.exists(opposite, NOFOLLOW_LINKS))
        }
    }

    @Test fun independentPeerParentSyncFailurePreventsReplacementWithoutAnUnsupportedSyncFallback() {
        for (layer in ConfigLayer.entries) for (nested in listOf(false, true)) for (unsupported in listOf(false, true)) {
            withPausedCreator(nested) { fixture, creator ->
                val syncs = mutableListOf<Path>()
                val beforeReplacements = mutableListOf<Path>()
                val afterReplacements = mutableListOf<Path>()
                val injected = if (unsupported) UnsupportedOperationException("Synthetic directory-sync unavailability")
                    else IOException("Synthetic parent directory-sync failure")
                val failure = assertFailsWith<ConfigReadException> {
                    val peer = ConfigStore(creator.stateRoot, TEST_ALIASES, fixture.authority, clock = fixture.clock,
                        writeObserver = object : ConfigWriteObserver {
                            override fun beforeDirectorySync(directory: Path) {
                                syncs.add(directory)
                                if (directory == creator.unconfirmedParent) throw injected
                            }
                            override fun beforeAtomicReplace(temporary: Path, destination: Path) { beforeReplacements.add(destination) }
                            override fun afterAtomicReplace(destination: Path) { afterReplacements.add(destination) }
                        })
                    val initial = peer.capture(fixture.binding)
                    val request = ConfigMutationRequest(fixture.binding, layer, initial.revisionToken,
                        ConfigAction.SetRuleEnabled(RULE, false), UUID.randomUUID().toString())
                    peer.mutate(request, fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant()))
                }
                assertEquals("Configuration directory synchronization failed; durability is unconfirmed.", failure.message)
                assertSame(injected, failure.cause)
                assertEquals(listOf(creator.directory, creator.unconfirmedParent), syncs)
                assertTrue(beforeReplacements.isEmpty())
                assertTrue(afterReplacements.isEmpty())
                creator.assertPaused()
                Files.list(creator.directory).use { children ->
                    assertEquals(0L, children.count(), "Failed parent sync must prevent creation of policy descendants")
                }

                val original = creator.releaseAndGet()
                val paths = original.storagePaths(fixture.binding)
                assertFalse(Files.exists(paths.shared, NOFOLLOW_LINKS))
                assertFalse(Files.exists(paths.local, NOFOLLOW_LINKS))
                val empty = original.capture(fixture.binding)
                for (candidate in listOf(empty.shared, empty.local)) {
                    assertEquals(0L, candidate.revision.revision)
                    assertTrue(candidate.auditEvents.isEmpty())
                    assertTrue(candidate.ruleSettings.isEmpty())
                }
                assertFalse(Files.exists(paths.shared, NOFOLLOW_LINKS))
                assertFalse(Files.exists(paths.local, NOFOLLOW_LINKS))
            }
        }
    }

    private fun withPausedCreator(nested: Boolean, block: (ConfigFixture, PausedCreator) -> Unit) {
        val fixture = ConfigFixture()
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val firstNewDirectory = fixture.root.resolve("unconfirmed-root")
            val stateRoot = if (nested) firstNewDirectory.resolve("nested").resolve("state") else firstNewDirectory
            assertFalse(Files.exists(firstNewDirectory, NOFOLLOW_LINKS))
            val pending = pool.submit<ConfigStore> {
                ConfigStore(stateRoot, TEST_ALIASES, fixture.authority, clock = fixture.clock,
                    writeObserver = object : ConfigWriteObserver {
                        override fun beforeDirectorySync(directory: Path) {
                            if (directory == firstNewDirectory) {
                                // The path started absent. This first hook is after creation but
                                // before this writer has synced either the new directory or its parent.
                                ready.countDown()
                                assertTrue(release.await(WAIT_SECONDS, TimeUnit.SECONDS), "Creator release timed out")
                            }
                        }
                    })
            }
            assertTrue(ready.await(WAIT_SECONDS, TimeUnit.SECONDS), "Directory-creation barrier timed out")
            val creator = PausedCreator(stateRoot, firstNewDirectory, release, pending)
            creator.assertPaused()
            assertTrue(Files.isDirectory(firstNewDirectory, NOFOLLOW_LINKS))
            if (nested) assertFalse(Files.exists(stateRoot, NOFOLLOW_LINKS))
            block(fixture, creator)
        } finally {
            release.countDown()
            pool.shutdown()
            var joined = false
            try {
                joined = pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
                if (!joined) {
                    pool.shutdownNow()
                    joined = pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
                }
                assertTrue(joined, "Synthetic directory creator must terminate before fixture cleanup")
            } finally {
                // Do not delete a fixture out from under a worker if bounded teardown fails.
                if (joined) fixture.close()
            }
        }
    }

    private class PausedCreator(
        val stateRoot: Path,
        val directory: Path,
        private val release: CountDownLatch,
        private val pending: Future<ConfigStore>,
    ) {
        val unconfirmedParent: Path get() = directory.parent
        fun assertPaused() {
            assertEquals(1L, release.count, "The creator must not be released by the peer")
            assertFalse(pending.isDone, "The creator must still be waiting before its directory sync")
        }
        fun releaseAndGet(): ConfigStore {
            release.countDown()
            return pending.get(WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun inspection(snapshot: ConfigSnapshot, layer: ConfigLayer): LayerInspection =
        if (layer == ConfigLayer.SHARED) snapshot.shared else snapshot.local

    private fun oppositeLayer(layer: ConfigLayer): ConfigLayer =
        if (layer == ConfigLayer.SHARED) ConfigLayer.LOCAL else ConfigLayer.SHARED

    companion object { private const val WAIT_SECONDS = 15L }
}

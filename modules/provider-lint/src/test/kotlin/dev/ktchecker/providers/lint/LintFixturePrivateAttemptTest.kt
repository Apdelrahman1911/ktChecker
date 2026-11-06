package dev.ktchecker.providers.lint

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.DirectoryStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import kotlinx.serialization.json.*
import kotlin.test.*

/** Documentary copying controls only; synthetic outcomes never authorize execution. */
class LintFixturePrivateAttemptTest {
    @Test fun originalFailureFlagsArgvIdentitiesAndUtf8DigestsArePrivateAndUnqualified() {
        val work = Files.createTempDirectory("ktchecker-lint-private-").toRealPath()
        val fixture = Files.createDirectory(work.resolve("fixture"))
        if (privateIoUnavailable(work, fixture)) return
        val destination = work.resolve("attempt")
        val input = input(fixture)
        val attempt = ProcessOutcome("failed", listOf("execution_timeout"), 137,
            stdout = "PRIVATE stdout \u03a9", stderr = "PRIVATE stderr \u00e9",
            outputDirectory = work.resolve("original-output"), command = listOf("/selected/gradlew", ":lintAnalyzeAndroidMain"),
            cancelled = true, timedOut = true, truncated = true, metrics = AnalysisMetrics(elapsedMillis = 7),
            supervisorIdentity = OwnedSupervisorIdentity("original-protocol", "linux-x86_64", Digests.sha256("source"), Digests.sha256("binary")),
            executionConfirmed = true)
        val console = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        try {
            PrintStream(console, true, Charsets.UTF_8).use { stream ->
                System.setOut(stream); System.setErr(stream)
                LintFixturePrivateAttempt.retain(destination, fixture, input.snapshot.snapshotId, input, attempt)
            }
        } finally {
            System.setOut(previousOut); System.setErr(previousErr)
        }
        assertEquals("", console.toString(Charsets.UTF_8))
        val metadataText = Files.readString(destination.resolve("attempt.json"))
        assertFalse(metadataText.contains("PRIVATE stdout"))
        assertFalse(metadataText.contains("PRIVATE stderr"))
        val metadata = ContractJson.parseToJsonElement(metadataText).jsonObject
        assertTrue(metadata.getValue("diagnosticOnly").jsonPrimitive.boolean)
        assertEquals(input.snapshot.snapshotId, metadata.getValue("sourceSnapshot").jsonObject.getValue("snapshotId").jsonPrimitive.content)
        val process = metadata.getValue("process").jsonObject
        assertEquals("failed", process.getValue("status").jsonPrimitive.content)
        assertEquals(137, process.getValue("exitCode").jsonPrimitive.int)
        assertEquals(attempt.command, process.getValue("command").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(attempt.reasons, process.getValue("reasons").jsonArray.map { it.jsonPrimitive.content })
        for (flag in listOf("executionConfirmed", "executed", "cancelled", "timedOut", "truncated")) {
            assertTrue(process.getValue(flag).jsonPrimitive.boolean, flag)
        }
        assertFalse(process.getValue("successfulExecution").jsonPrimitive.boolean)
        assertEquals(JsonNull, process.getValue("outputIdentityOriginal"), "Never fabricate unavailable stored identity")
        assertEquals("original-protocol", process.getValue("supervisorIdentity").jsonObject.getValue("protocolVersion").jsonPrimitive.content)
        assertEquals(7, process.getValue("metrics").jsonObject.getValue("elapsedMillis").jsonPrimitive.int)
        for ((name, text) in listOf("stdout" to attempt.stdout, "stderr" to attempt.stderr)) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            assertContentEquals(bytes, Files.readAllBytes(destination.resolve(name + ".log")))
            assertEquals(bytes.size, metadata.getValue(name + "Bytes").jsonPrimitive.int)
            assertEquals(Digests.sha256(bytes), metadata.getValue(name + "Digest").jsonPrimitive.content)
        }
        assertEquals(Digests.sha256(attempt.stdout + "\n" + attempt.stderr), metadata.getValue("combinedOutputDigest").jsonPrimitive.content)
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(destination))
        for (name in listOf("stdout.log", "stderr.log", "attempt.json")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(destination.resolve(name)))
        }
        assertFalse(metadataText.contains("certificateId"))
    }

    @Test fun noAttemptRemainsExplicitlyAbsentRatherThanAnInventedZeroExit() {
        val work = Files.createTempDirectory("ktchecker-lint-private-absent-").toRealPath()
        if (privateIoUnavailable(work, work.resolve("missing-fixture"))) return
        val destination = work.resolve("attempt")
        LintFixturePrivateAttempt.retain(destination, work.resolve("missing-fixture"), "", null, null)
        val metadata = ContractJson.parseToJsonElement(Files.readString(destination.resolve("attempt.json"))).jsonObject
        assertFalse(metadata.getValue("lintAttemptCaptured").jsonPrimitive.boolean)
        assertEquals(JsonNull, metadata.getValue("process"))
        assertEquals(JsonNull, metadata.getValue("sourceSnapshot"))
        assertEquals(JsonNull, metadata.getValue("declaredExecution"))
        assertEquals(0L, Files.size(destination.resolve("stdout.log")))
        assertEquals(0L, Files.size(destination.resolve("stderr.log")))
    }

    @Test fun freshPrivateDestinationCannotOverwriteOrOverlapOriginalOrMaterializedSources() {
        val work = Files.createTempDirectory("ktchecker-lint-private-paths-").toRealPath()
        val fixture = Files.createDirectory(work.resolve("fixture"))
        if (privateIoUnavailable(work, fixture)) return
        val frozen = Files.createDirectory(work.resolve("frozen"))
        val input = input(frozen)
        val attempt = ProcessOutcome("unavailable")
        val existing = work.resolve("existing")
        Files.writeString(existing, "preserve")
        assertReason("lint_private_attempt_destination_exists") {
            LintFixturePrivateAttempt.retain(existing, fixture, input.snapshot.snapshotId, input, attempt)
        }
        assertEquals("preserve", Files.readString(existing))
        for (destination in listOf(fixture.resolve("new"), frozen.resolve("new"))) {
            assertReason("lint_private_attempt_source_overlap") {
                LintFixturePrivateAttempt.retain(destination, fixture, input.snapshot.snapshotId, input, attempt)
            }
            assertFalse(Files.exists(destination))
        }
        val alias = Files.createSymbolicLink(work.resolve("fixture-alias"), fixture)
        val parentAlias = Files.createSymbolicLink(work.resolve("parent-alias"), work)
        for (destination in listOf(alias.resolve("new"), parentAlias.resolve("new"), fixture.resolve("../new"))) {
            assertReason("lint_private_attempt_destination_alias") {
                LintFixturePrivateAttempt.retain(destination, fixture, input.snapshot.snapshotId, input, attempt)
            }
            assertFalse(Files.exists(destination, NOFOLLOW_LINKS))
        }
        val link = Files.createSymbolicLink(work.resolve("destination-link"), existing)
        assertReason("lint_private_attempt_destination_exists") {
            LintFixturePrivateAttempt.retain(link, fixture, input.snapshot.snapshotId, input, attempt)
        }
        assertEquals("preserve", Files.readString(existing))
    }

    @Test fun combinedUtf8AndMetadataLimitsRejectWithoutPartialOutputOrSilentTruncation() {
        val work = Files.createTempDirectory("ktchecker-lint-private-bounds-").toRealPath()
        val fixture = Files.createDirectory(work.resolve("fixture"))
        if (privateIoUnavailable(work, fixture)) return
        val input = input(fixture)
        val destination = work.resolve("attempt")
        for (attempt in listOf(
            ProcessOutcome("failed", stdout = "a".repeat(LintFixturePrivateAttempt.MAX_OUTPUT_BYTES / 2 + 1),
                stderr = "b".repeat(LintFixturePrivateAttempt.MAX_OUTPUT_BYTES / 2 + 1)),
            ProcessOutcome("failed", stdout = "\u00e9".repeat(LintFixturePrivateAttempt.MAX_OUTPUT_BYTES / 2 + 1)),
        )) {
            assertReason("lint_private_attempt_output_limit") {
                LintFixturePrivateAttempt.retain(destination, fixture, input.snapshot.snapshotId, input, attempt)
            }
            assertFalse(Files.exists(destination))
        }
        assertReason("lint_private_attempt_metadata_limit") {
            LintFixturePrivateAttempt.retain(destination, fixture, input.snapshot.snapshotId, input,
                ProcessOutcome("failed", command = listOf("x".repeat(LintFixturePrivateAttempt.MAX_METADATA_BYTES + 1))))
        }
        assertFalse(Files.exists(destination))
        assertReason("lint_private_attempt_descriptor_missing") {
            LintFixturePrivateAttempt.retain(destination, fixture, "", null, ProcessOutcome("unavailable"))
        }
        assertFalse(Files.exists(destination))
    }

    @Test fun parentMustBeCurrentOwnerAndExactlyPrivateNotMerelyPosixCapable() {
        val work = Files.createTempDirectory("ktchecker-lint-private-permissions-").toRealPath()
        val fixture = Files.createDirectory(work.resolve("fixture"))
        if (privateIoUnavailable(work, fixture)) return
        val attributes = Files.readAttributes(work, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
        val foreignOwner = object : UserPrincipal { override fun getName() = "not-the-process-owner" }
        assertTrue(LintFixturePrivateAttempt.isOwnerPrivateDirectory(attributes, attributes.owner()))
        assertFalse(LintFixturePrivateAttempt.isOwnerPrivateDirectory(attributes, foreignOwner))
        val foreignAttributes = object : PosixFileAttributes by attributes { override fun owner() = foreignOwner }
        assertFalse(LintFixturePrivateAttempt.isOwnerPrivateDirectory(foreignAttributes, attributes.owner()))
        val destination = work.resolve("attempt")
        try {
            for (permissions in listOf("rwxr-xr-x", "rwx--x---", "rwx---r--", "r-x------")) {
                Files.setPosixFilePermissions(work, PosixFilePermissions.fromString(permissions))
                assertReason("lint_private_attempt_parent_not_private") { LintFixturePrivateAttempt.requireSupportedParent(work) }
                assertReason("lint_private_attempt_parent_not_private") {
                    LintFixturePrivateAttempt.retain(destination, fixture, "", null, null)
                }
                assertFalse(Files.exists(destination, NOFOLLOW_LINKS))
                assertEquals(PosixFilePermissions.fromString(permissions), Files.getPosixFilePermissions(work), "No automatic chmod of the selected parent")
            }
        } finally { Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("rwx------")) }
    }

    @Test fun nonPosixFilesystemExplicitlyRefusesRetentionWithoutCreatingTheDestination() {
        val work = Files.createTempDirectory("ktchecker-lint-private-capability-").toRealPath()
        val archive = work.resolve("non-posix.zip")
        FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), mapOf("create" to "true", "enablePosixFileAttributes" to "false")).use { fileSystem ->
            val parent = Files.createDirectory(fileSystem.getPath("/private-parent"))
            val destination = parent.resolve("attempt")
            assertReason("lint_private_attempt_permissions_unavailable") { LintFixturePrivateAttempt.requireSupportedParent(parent) }
            assertReason("lint_private_attempt_permissions_unavailable") {
                LintFixturePrivateAttempt.retain(destination, fileSystem.getPath("/fixture"), "", null, null)
            }
            assertFalse(Files.exists(destination, NOFOLLOW_LINKS))
            Files.newDirectoryStream(parent).use { assertFalse(it.iterator().hasNext()) }
        }
    }

    @Test fun ordinaryDirectoryStreamsAreRefusedNotTreatedAsSecureIo() {
        var closed = false
        val ordinary = object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> = error("Capability refusal must not enumerate entries")
            override fun close() { closed = true }
        }
        ordinary.use {
            assertReason("lint_private_attempt_secure_io_unavailable") { LintFixturePrivateAttempt.secureDirectory(it) }
        }
        assertTrue(closed)
    }

    @Test fun parentAndAncestorReplacementCannotRedirectNewFilesOrOpenedChannels() {
        for (subject in listOf("parent", "ancestor")) {
            for (stage in listOf("parent_opened", "before_open:stdout.log", "before_write:stdout.log")) {
                for (link in listOf(false, true)) replacementControl(subject, stage, link)
            }
        }
    }

    @Test fun createdDirectoryBindingIsCheckedBeforeOpeningAndAfterWrites() {
        for (stage in listOf("directory_created", "directory_opened", "before_open:stdout.log", "before_write:stdout.log", "after_write:stdout.log")) {
            for (link in listOf(false, true)) replacementControl("directory", stage, link)
        }
    }

    @Test fun pathnameOnlyMkdirRedirectionCannotAuthorizeAnyPrivateFileWrites() {
        val work = Files.createTempDirectory("ktchecker-lint-private-mkdir-").toRealPath()
        val fixture = Files.createDirectory(work.resolve("fixture"))
        if (privateIoUnavailable(work, fixture)) return
        val parent = privateDirectory(work.resolve("parent"))
        val outside = privateDirectory(work.resolve("outside"))
        val saved = work.resolve("original-parent")
        val input = input(fixture)
        var mutated = false
        assertReason("lint_private_attempt_binding_changed") {
            LintFixturePrivateAttempt.retain(parent.resolve("attempt"), fixture, input.snapshot.snapshotId, input,
                ProcessOutcome("failed", stdout = "PRIVATE bytes")) { checkpoint ->
                if (checkpoint == "before_directory_create") {
                    Files.move(parent, saved)
                    Files.createSymbolicLink(parent, outside)
                    mutated = true
                }
            }
        }
        assertTrue(mutated)
        assertFalse(Files.exists(saved.resolve("attempt"), NOFOLLOW_LINKS))
        // Explicit Java mkdir-at limitation: a race may leave an EMPTY 0700 candidate in
        // a redirected namespace. It is not the held parent's child and receives no bytes.
        val emptyCandidate = outside.resolve("attempt")
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(emptyCandidate))
        Files.newDirectoryStream(emptyCandidate).use { assertFalse(it.iterator().hasNext()) }
        assertTrue(Files.isSymbolicLink(parent), "Do not follow or repair changed namespace during failure cleanup")
    }

    @Test fun leafSymlinkCannotOverwriteAnOutsideFileEvenAfterChannelCreation() {
        for (stage in listOf("before_open:stdout.log", "before_write:stdout.log")) {
            val work = Files.createTempDirectory("ktchecker-lint-private-leaf-").toRealPath()
            val fixture = Files.createDirectory(work.resolve("fixture"))
            if (privateIoUnavailable(work, fixture)) continue
            val destination = work.resolve("attempt")
            val outside = Files.writeString(work.resolve("outside.txt"), "preserve")
            val saved = work.resolve("original-stdout.log")
            val input = input(fixture)
            val attempt = ProcessOutcome("failed", stdout = "PRIVATE original bytes")
            var mutated = false
            assertReason("lint_private_attempt_binding_changed") {
                LintFixturePrivateAttempt.retain(destination, fixture, input.snapshot.snapshotId, input, attempt) { checkpoint ->
                    if (checkpoint == stage) {
                        if (stage.startsWith("before_write")) Files.move(destination.resolve("stdout.log"), saved)
                        Files.createSymbolicLink(destination.resolve("stdout.log"), outside)
                        mutated = true
                    }
                }
            }
            assertTrue(mutated)
            assertEquals("preserve", Files.readString(outside))
            assertTrue(Files.isSymbolicLink(destination.resolve("stdout.log")), "No cleanup or following of a replacement entry")
            if (stage.startsWith("before_write")) assertEquals(attempt.stdout, Files.readString(saved), "The held new channel cannot be redirected by a leaf replacement")
            assertFalse(Files.exists(destination.resolve("stderr.log"), NOFOLLOW_LINKS))
            assertFalse(Files.exists(destination.resolve("attempt.json"), NOFOLLOW_LINKS))
        }
    }

    @Test fun permissionMutationRefusesBeforeAnyPrivateByteAndDoesNotRepairTheNamespace() {
        for (subject in listOf("parent", "directory")) {
            val work = Files.createTempDirectory("ktchecker-lint-private-mode-drift-").toRealPath()
            val fixture = Files.createDirectory(work.resolve("fixture"))
            if (privateIoUnavailable(work, fixture)) continue
            val destination = work.resolve("attempt")
            val changed = if (subject == "parent") work else destination
            val input = input(fixture)
            try {
                assertReason("lint_private_attempt_binding_changed") {
                    LintFixturePrivateAttempt.retain(destination, fixture, input.snapshot.snapshotId, input,
                        ProcessOutcome("failed", stdout = "PRIVATE bytes")) { checkpoint ->
                        if (checkpoint == "before_open:stdout.log") {
                            Files.setPosixFilePermissions(changed, PosixFilePermissions.fromString("rwxr-xr-x"))
                        }
                    }
                }
                assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(changed))
                assertEquals(0L, Files.size(destination.resolve("stdout.log")))
                assertFalse(Files.exists(destination.resolve("stderr.log"), NOFOLLOW_LINKS))
                assertFalse(Files.exists(destination.resolve("attempt.json"), NOFOLLOW_LINKS))
            } finally { Files.setPosixFilePermissions(changed, PosixFilePermissions.fromString("rwx------")) }
        }
    }

    private fun replacementControl(subject: String, stage: String, link: Boolean) {
        val work = Files.createTempDirectory("ktchecker-lint-private-rebinding-").toRealPath()
        val fixture = Files.createDirectory(work.resolve("fixture"))
        if (privateIoUnavailable(work, fixture)) return
        val ancestor = privateDirectory(work.resolve("ancestor"))
        val parent = privateDirectory(ancestor.resolve("parent"))
        val destination = parent.resolve("attempt")
        val replaced = when (subject) { "ancestor" -> ancestor; "parent" -> parent; else -> destination }
        val outside = privateDirectory(work.resolve("outside"))
        val suffix = when (subject) { "ancestor" -> listOf("parent", "attempt"); "parent" -> listOf("attempt"); else -> emptyList() }
        var outsideAttempt = outside
        for (name in suffix) outsideAttempt = privateDirectory(outsideAttempt.resolve(name))
        Files.writeString(outsideAttempt.resolve("keep.txt"), "preserve")
        val saved = work.resolve("original-$subject")
        val input = input(fixture)
        val attempt = ProcessOutcome("failed", stdout = "PRIVATE original bytes", stderr = "PRIVATE stderr")
        var mutated = false
        assertReason("lint_private_attempt_binding_changed") {
            LintFixturePrivateAttempt.retain(destination, fixture, input.snapshot.snapshotId, input, attempt) { checkpoint ->
                if (checkpoint == stage) {
                    Files.move(replaced, saved)
                    if (link) Files.createSymbolicLink(replaced, outside) else Files.move(outside, replaced)
                    mutated = true
                }
            }
        }
        assertTrue(mutated, "$subject $stage link=$link")
        val replacementAttempt = suffix.fold(if (link) outside else replaced) { path, name -> path.resolve(name) }
        assertEquals("preserve", Files.readString(replacementAttempt.resolve("keep.txt")))
        for (name in listOf("stdout.log", "stderr.log", "attempt.json")) {
            assertFalse(Files.exists(replacementAttempt.resolve(name), NOFOLLOW_LINKS), "No diagnostic byte may be redirected into a replacement")
        }
        assertEquals(link, Files.isSymbolicLink(replaced), "No removal or repair of a replaced namespace")
        val originalAttempt = suffix.fold(saved) { path, name -> path.resolve(name) }
        when (stage) {
            "parent_opened" -> assertFalse(Files.exists(originalAttempt, NOFOLLOW_LINKS))
            "directory_created", "directory_opened" -> Files.newDirectoryStream(originalAttempt).use { assertFalse(it.iterator().hasNext()) }
            "before_open:stdout.log" -> assertEquals(0L, Files.size(originalAttempt.resolve("stdout.log")))
            else -> assertEquals(attempt.stdout, Files.readString(originalAttempt.resolve("stdout.log")), "Opened channels stay attached to the original private directory")
        }
        assertFalse(Files.exists(originalAttempt.resolve("stderr.log"), NOFOLLOW_LINKS))
        assertFalse(Files.exists(originalAttempt.resolve("attempt.json"), NOFOLLOW_LINKS))
    }

    private fun privateDirectory(path: Path) = Files.createDirectory(path,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))

    private fun input(root: Path) = AnalysisInput(
        SnapshotManifest(Digests.sha256("private diagnostic test only"), "fixture", "test", emptyList()),
        root.toString(), execution = TrustedExecution("/selected/gradlew", listOf(":lintAnalyzeAndroidMain"),
            environment = mapOf("JAVA_HOME" to "/selected/jdk25")),
    )

    private fun privateIoUnavailable(work: Path, fixture: Path): Boolean {
        val reason = try {
            LintFixturePrivateAttempt.requireSupportedParent(work)
            return false
        } catch (failure: ExecutionProblem) {
            assertContains(setOf("lint_private_attempt_permissions_unavailable", "lint_private_attempt_secure_io_unavailable"), failure.code)
            failure.code
        }
        val before = Files.newDirectoryStream(work).use { it.map { path -> path.fileName.toString() }.toSet() }
        val destination = work.resolve("unsupported-attempt")
        assertReason(reason) {
            LintFixturePrivateAttempt.retain(destination, fixture, "", null, null)
        }
        assertFalse(Files.exists(destination, NOFOLLOW_LINKS))
        assertEquals(before, Files.newDirectoryStream(work).use { it.map { path -> path.fileName.toString() }.toSet() })
        return true
    }

    private fun assertReason(reason: String, block: () -> Unit) {
        val failure = assertFailsWith<ExecutionProblem>(block = block)
        assertEquals(reason, failure.code)
        assertFalse(failure.message.orEmpty().contains("ktchecker-lint-private-"), "Private paths must not escape through an I/O failure")
        assertFalse(failure.message.orEmpty().contains("PRIVATE"))
        assertNull(failure.cause, "Do not attach a raw I/O exception or process content")
        assertTrue(failure.suppressed.isEmpty(), "Do not attach raw close/I/O failures")
    }
}

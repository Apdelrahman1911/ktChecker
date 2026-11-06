package dev.ktchecker.cli

import dev.ktchecker.contracts.*
import dev.ktchecker.config.ConfigValidationException
import dev.ktchecker.kernel.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real controller/SQLite/CAS integration. No provider qualification or model-delivery claim. */
class WorkbenchTest {
    private fun options(): WorkbenchOptions = WorkbenchOptions(Files.createTempDirectory("ktchecker-controller-雪-"), workerCount = 0)
    private fun fixture(work: Workbench, client: String = "agent-a"): PreparedReview {
        work.register("project", "tree", client, "session-$client", "lineage-$client")
        val commit = work.overlay(OverlayDocument("project", "tree", client, "session-$client", "patch-1",
            changes = listOf(OverlayChange("put", "Main.kt", content = "fun answer(): Int = 42\n"))))
        return work.prepare(PreparationSpec(client, commit.snapshotId, "patch-1", "request-1", listOf("Main.kt"),
            requiredRuleIds = listOf("ktc.compiler.common.abstract_class_member_not_implemented"), addKotlinStdlib = false), false)
    }

    @Test fun realImmutableSubmissionSurvivesControllerRestartAndDuplicate() {
        val options = options()
        lateinit var saved: PreparedReview
        lateinit var credential: ClientCredential
        lateinit var receipt: ReviewStatus
        Workbench(options).use { work ->
            saved = fixture(work)
            credential = work.registration("agent-a").client
            receipt = work.submit(credential, saved.patchId, saved.idempotencyKey, saved.input)
            assertEquals("queued", receipt.state)
            assertEquals(receipt.reviewId, work.submit(credential, saved.patchId, saved.idempotencyKey, saved.input).reviewId)
            assertFalse(saved.input.context.fullProject)
            assertFalse(saved.executionAuthorized)
        }
        Workbench(options).use { work ->
            assertEquals(receipt.reviewId, work.submit(credential, saved.patchId, saved.idempotencyKey, saved.input).reviewId)
            assertEquals(credential, work.registration("agent-a").client)
            val accepted = work.kernel.events(credential).events
            assertEquals(1, accepted.size)
            assertEquals(receipt.reviewId, accepted.single().reviewId)
        }
    }

    @Test fun callerCannotForgeProviderAuthorizationOrOtherClientPreparedInputs() {
        Workbench(options()).use { work ->
            val saved = fixture(work)
            val other = work.register("project", "tree", "agent-b", "session-b", "lineage-b")
            assertFailsWith<CliProblem> { work.submit(other.client, saved.patchId, saved.idempotencyKey, saved.input) }
            val forged = saved.input.copy(execution = TrustedExecution("/bin/sh", listOf("-c", "echo unsafe"), true))
            assertFailsWith<CliProblem> { work.submit(work.registration("agent-a").client, saved.patchId, saved.idempotencyKey, forged) }
            assertFailsWith<CliProblem> { work.submit(work.registration("agent-a").client, saved.patchId, "other-key", saved.input) }
            assertTrue(work.kernel.events(work.registration("agent-a").client).events.isEmpty())
        }
    }

    @Test fun sharedClientEnrollmentIsIdempotentAndBindingsCannotMove() {
        val options = options()
        Workbench(options).use { work ->
            val expected = work.register("project", "tree", "client", "session", "lineage")
            val pool = Executors.newFixedThreadPool(2)
            try {
                val calls = pool.invokeAll(List(4) { Callable { work.register("project", "tree", "client", "session", "lineage") } })
                calls.forEach { assertEquals(expected, it.get()) }
            } finally { pool.shutdownNow() }
            assertFailsWith<CliProblem> { work.register("project", "different-tree", "client", "session", "lineage") }
        }
        Workbench(options).use { work -> assertEquals("session", work.registration("client").binding.sessionId) }
    }

    @Test fun unknownAndPrivilegedMutationMethodsAreNotReviewOperations() {
        Workbench(options()).use { work ->
            val rpc = RpcServer(work)
            listOf("config.mutate", "snapshot.edit", "exec", "review.prepare").forEach { method ->
                val value = rpc.handle("""{"id":"x","method":"$method","params":{},"findingWireProfile":"$FINDING_WIRE_PROFILE"}""")
                assertEquals("method_not_found", value.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            }
        }
    }

    @Test fun privateStateRejectsExistingProjectOrWorldReadableStoreWithoutChangingPermissions() {
        val parent = Files.createTempDirectory("ktchecker-state-boundary-")
        val foreign = Files.createDirectory(parent.resolve("project"))
        Files.writeString(foreign.resolve("source.kt"), "val protected = 1")
        assertFailsWith<CliProblem> { PrivateState(foreign) }
        assertEquals("val protected = 1", Files.readString(foreign.resolve("source.kt")))
        val path = parent.resolve("state")
        PrivateState(path)
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
            assertFailsWith<CliProblem> { PrivateState(path) }
            assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(path))
        }
    }

    @Test fun ownerIntentCanBeReplayedAfterBrokerEnrollmentButBeforeResponse() {
        val options = options()
        lateinit var identity: ClientRegistration
        Workbench(options).use { work -> identity = work.register("project", "tree", "intent-client", "session", "lineage") }
        Workbench(options).use { work ->
            assertEquals(identity, work.register("project", "tree", "intent-client", "session", "lineage"))
            assertEquals(identity, work.kernel.registerClient(identity))
        }
    }

    @Test fun catalogAndUninstalledProviderRoutesCannotMintNarrowFindingIdentity() {
        Workbench(options()).use { work ->
            val saved = fixture(work)
            val before = work.inspectConfig("agent-a")
            val file = saved.input.snapshot.files.single()
            listOf(
                "ktc.architecture.allowed_dependency_edge" to "architecture",
                "ktc.kotlin.detekt.style.magic-number" to "detekt",
                "ktc.android.lint.NewApi" to "android-lint",
            ).forEach { (id, provider) ->
                val finding = Finding(id, "Synthetic warning", "Control", "Identity test only",
                    SourceLocation(file.path, file.digest), "untrusted-fingerprint", provider, saved.input.context.scope)
                assertFailsWith<ConfigValidationException> { work.findingSelector("agent-a", saved.input, finding) }
                assertFailsWith<CliProblem> { work.findingSelector("other-consumer", saved.input, finding) }
            }
            assertEquals(before, work.inspectConfig("agent-a"))
        }
    }

    @Test fun invalidWaitOptionRejectsBeforeAcceptingPatchOrReadingPreparedInput() {
        val options = options()
        lateinit var saved: PreparedReview
        val path = Files.createTempDirectory("ktchecker-owner-submission-").resolve("prepared.json")
        Workbench(options).use { work -> saved = fixture(work); saveOwnerFile(path, result(saved)) }
        listOf("-1", "nonsense", "300001").forEach { wait ->
            assertEquals(3, runCli(arrayOf("--state", options.state.toString(), "--workers", "0", "submit",
                "--prepared", path.toString(), "--wait-ms", wait)))
        }
        Workbench(options).use { work ->
            assertTrue(work.kernel.events(work.registration("agent-a").client).events.isEmpty())
        }
    }

    @Test fun explicitAlternateParserVersionIsPreservedButCannotBorrowBundledStdlib() {
        Workbench(options()).use { work ->
            val base = fixture(work)
            val spec = PreparationSpec("agent-a", base.input.snapshot.snapshotId, "syntax-patch", "syntax-request",
                listOf("Main.kt"), requiredRuleIds = listOf("ktc.compose.pack.ComposableParamOrder"),
                addKotlinStdlib = false, compilerVersion = "2.2.21")
            val selected = work.prepare(spec, false)
            assertEquals("2.2.21", selected.input.context.compilerVersion)
            assertTrue(selected.input.context.classpath.isEmpty())
            assertNull(selected.input.context.toolchainDigest)
            assertNotEquals(base.input.context.modelDigest, selected.input.context.modelDigest)
            assertFalse(selected.input.context.fullProject)
            assertFalse(selected.executionAuthorized)
            assertEquals("matching_stdlib_unavailable", assertFailsWith<CliProblem> {
                work.prepare(spec.copy(addKotlinStdlib = true), false)
            }.code)
            listOf("", "latest", "2.2", "2.2.21\n", "2.2.21-" + "x".repeat(65)).forEach { version ->
                assertEquals("invalid_compiler_version", assertFailsWith<CliProblem> {
                    work.prepare(spec.copy(compilerVersion = version), false)
                }.code)
            }
        }
    }

    @Test fun projectClasspathOrderAndRepeatedEntriesRemainInAcceptedModelIdentity() {
        Workbench(options()).use { work ->
            val base = fixture(work)
            val root = Files.createTempDirectory("ktchecker-classpath-order-")
            fun artifact(name: String, text: String) = ArtifactIdentity("example:$name:1", Digests.sha256(text),
                root.resolve("$name.jar").also { Files.writeString(it, text) }.toString())
            // File identities are controls for prepare(), NOT claims of executable valid JARs.
            val z = artifact("z", "first-definition")
            val a = artifact("a", "second-definition")
            val spec = PreparationSpec("agent-a", base.input.snapshot.snapshotId, "classpath-patch", "classpath-request",
                listOf("Main.kt"), requiredRuleIds = base.input.requiredRuleIds, addKotlinStdlib = false,
                classpath = listOf(z, a, z))
            val first = work.prepare(spec, false)
            val reversed = work.prepare(spec.copy(classpath = listOf(a, z, z)), false)
            assertEquals(listOf(z, a, z), first.input.context.classpath)
            assertEquals(listOf(a, z, z), reversed.input.context.classpath)
            assertNotEquals(first.input.context.dependencyDigest, reversed.input.context.dependencyDigest)
            assertNotEquals(first.input.analysisKey(), reversed.input.analysisKey())
            assertEquals("classpath_identity_conflict", assertFailsWith<CliProblem> {
                work.prepare(spec.copy(classpath = listOf(z, z.copy(digest = Digests.sha256("other")))), false)
            }.code)
        }
    }

    @Test fun invalidExplicitCompilerRuntimeDoesNotBreakCaptureSubmissionOrConcealUnavailableCoverage() {
        val artifacts = Files.createTempDirectory("ktchecker-unavailable-compiler-")
        Files.writeString(artifacts.resolve("kotlin-compiler-embeddable-2.4.21.jar"), "not-an-executable-compiler")
        val options = options().copy(compilerDirectory = artifacts,
            compilerJavaHome = artifacts.resolve("explicit-missing-jdk"), workerCount = 1)
        Workbench(options).use { work ->
            val selected = work.providerAvailability().getValue("compiler").jsonObject
            assertEquals("unavailable", selected.getValue("state").jsonPrimitive.content)
            assertTrue(selected.getValue("reason").jsonPrimitive.content.startsWith("compiler_java_runtime_"))
            assertEquals("explicit_owner_selection", selected.getValue("javaHomeSelection").jsonPrimitive.content)
            val saved = fixture(work)
            assertTrue(saved.profiles.isEmpty(), "An unavailable runtime cannot mint an executable profile")
            assertNull(saved.input.context.toolchainDigest)
            val credential = work.registration("agent-a").client
            var status = work.submit(credential, saved.patchId, saved.idempotencyKey, saved.input)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (status.result == null && System.nanoTime() < deadline) {
                Thread.sleep(10)
                status = work.kernel.status(credential, status.reviewId)
            }
            val observed = assertNotNull(status.result, "Unavailable analysis must still reach an explicit terminal result")
            assertTrue(observed.findings.isEmpty())
            assertEquals(saved.input.requiredRuleIds, observed.coverage.map { it.ruleId })
            assertTrue(observed.coverage.all { it.state == "unavailable" && it.reason!!.startsWith("compiler_java_runtime_") })
            assertFalse(observed.complete())
            assertEquals(2, reviewExit(status))
        }
    }
}

package dev.ktchecker.audit

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.SnapshotFile
import dev.ktchecker.contracts.SnapshotManifest
import dev.ktchecker.projectmodel.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/**
 * Real importer, byte checks and impact planner; intentionally SYNTHETIC trusted origin callbacks.
 * The tiny fake artifact files are never executed. These are not Gradle/model qualification tests.
 */
class ProjectModelAuditTest {
    @TempDir lateinit var temporary: Path
    private val namespace = ModelNamespace("audit-owner", "model-project", "left", "lineage-a")
    private val policy = Digests.sha256("TEST-ONLY owner policy")
    private val executions = AtomicInteger()

    private inner class Fixture {
        val root: Path = Files.createDirectory(temporary.resolve("capture")).toRealPath()
        private val artifacts = Files.createDirectory(temporary.resolve("fake-artifacts")).toRealPath()
        val paths = listOf("build.gradle.kts", "src/Api.kt", "src/Other.kt", "client/Use.kt",
            "inputs/spec.txt", "generated/Generated.kt")
        val compiler: ModelArtifact
        val runtime: ModelArtifact

        init {
            paths.forEach { path ->
                val destination = root.resolve(path)
                Files.createDirectories(destination.parent)
                Files.writeString(destination, "// Synthetic importer fixture: $path; never compiled.\n")
            }
            fun artifact(name: String): ModelArtifact {
                val path = artifacts.resolve(name)
                val bytes = "NOT EXECUTABLE: $name".toByteArray()
                Files.write(path, bytes)
                return ModelArtifact("audit:$name", path.toString(), Digests.sha256(bytes), bytes.size.toLong())
            }
            compiler = artifact("compiler.bin")
            runtime = artifact("runtime.bin")
        }

        // Match the importer's actual filesystem-root capability probe. Unexpected I/O
        // errors remain failures; an OS label or successful ordinary read grants nothing.
        fun anchoredReadsSupported(): Boolean =
            Files.newDirectoryStream(root.root).use { it is SecureDirectoryStream<*> }

        fun file(path: String): ModelFile {
            val bytes = Files.readAllBytes(root.resolve(path))
            return ModelFile(path, Digests.sha256(bytes), bytes.size.toLong())
        }

        fun graph(): GradleProjectExport {
            val api = file("src/Api.kt")
            val other = file("src/Other.kt")
            val generated = file("generated/Generated.kt")
            val client = file("client/Use.kt")
            fun sourceSet(id: String, module: String, files: List<ModelFile>) = ModelSourceSet(
                id, module, "main", emptyList(), listOf("src", "client", "generated"), emptyList(), files, emptyList())
            fun compilation(id: String, module: String, set: String, files: List<ModelFile>, variant: String,
                            name: String = "main", depends: List<ModelProjectDependency> = emptyList(),
                            associated: List<String> = emptyList()) = ModelCompilation(
                id = id, moduleId = module, target = "jvm", platform = "jvm", name = name,
                defaultSourceSetId = set, sourceSetIds = listOf(set), sourceFiles = files,
                compileTaskPath = "$module:compile${name.replaceFirstChar(Char::uppercaseChar)}Kotlin",
                dependencyConfiguration = "${name}CompileClasspath", classpath = listOf(runtime, compiler),
                projectDependencies = depends,
                toolchain = ModelToolchain("jvm", "2.4.21", listOf(compiler), listOf(runtime)),
                compilerArguments = listOf("-jvm-target", "17"), languageVersion = "2.4", apiVersion = "2.4",
                producedVariants = listOf(variant), associatedCompilationIds = associated)
            return GradleProjectExport(
                gradleVersion = "9.6.1",
                modules = listOf("a", "b", "c").map { ModelModule(":$it", "module-$it", null,
                    listOf("org.jetbrains.kotlin.jvm")) },
                sourceSets = listOf(sourceSet("a-set", ":a", listOf(api)),
                    sourceSet("b-set", ":b", listOf(other, generated)), sourceSet("c-set", ":c", listOf(client))),
                compilations = listOf(
                    compilation("a-main", ":a", "a-set", listOf(api), "aApiElements"),
                    compilation("a-test", ":a", "a-set", listOf(api), "aTestElements", name = "test",
                        associated = mutableListOf("a-main")),
                    compilation("b-main", ":b", "b-set", listOf(other, generated), "bApiElements"),
                    compilation("c-main", ":c", "c-set", listOf(client), "cApiElements", depends = listOf(
                        ModelProjectDependency(":a", "aApiElements", mapOf("usage" to "java-api"), "a-main")))),
                configurationInputs = listOf(file("build.gradle.kts")),
                generatedInputs = listOf(GeneratedSourceInput(":b:generate", listOf("b-set"),
                    listOf(file("inputs/spec.txt")), listOf(generated), listOf("generated"), "captured",
                    mutableMapOf("fixture-mode" to "synthetic"))),
                buildscriptArtifacts = emptyList(), includedBuilds = emptyList(), issues = emptyList())
        }

        fun request(graph: GradleProjectExport, identity: ModelNamespace = namespace): ModelImportRequest {
            val files = paths.sorted().map(::file).map { SnapshotFile(it.path, it.digest, it.size) }
            val snapshot = SnapshotManifest(Digests.sha256("TEST-ONLY capture:" + ContractJson.encodeToString(files)),
                identity.workspaceId, identity.worktreeId, files, ownerId = identity.ownerId,
                captureKind = "immutable_overlay", declaredRoots = listOf("synthetic-whole-capture"))
            return ModelImportRequest(identity, snapshot, root.toString(), policy,
                Digests.sha256(bytes(graph)), GradleModelExporter.scriptDigest(), "synthetic-export-${executions.incrementAndGet()}")
        }

        fun permit(request: ModelImportRequest, graph: GradleProjectExport,
                   completeInputs: (GradleProjectExport) -> Unit = { assertEquals(graph, it) }): ModelIngressPermit =
            ModelIngressPermit(request, Digests.sha256("TEST-ONLY Gradle runtime"), verifySnapshot = {
                request.snapshot.files.forEach {
                    val bytes = Files.readAllBytes(root.resolve(it.path))
                    assertEquals(it.size, bytes.size.toLong())
                    assertEquals(it.digest, Digests.sha256(bytes))
                }
            }, verifyExecutedExport = {
                // This tests a privileged callback boundary, not evidence of real Gradle execution.
                assertTrue(request.executionId.startsWith("synthetic-export-"))
            }, verifyArtifact = {
                assertEquals(it.digest, Digests.sha256(Files.readAllBytes(Path.of(it.path))))
            }, verifyModelInputs = completeInputs)

        fun admit(graph: GradleProjectExport = graph(), identity: ModelNamespace = namespace): VerifiedProjectModel {
            val request = request(graph, identity)
            return ProjectModelImporter(ExactModelIngressAuthority(permit(request, graph))).import(request, bytes(graph))
        }
    }

    private fun bytes(graph: GradleProjectExport) = ContractJson.encodeToString(graph).toByteArray()

    private fun anchoredFixture(): Fixture = Fixture().also { fixture ->
        // Abort before any assertFailsWith wrapper: unavailable anchored reads must not
        // become evidence that an unrelated graph/authority negative control passed.
        assumeTrue(fixture.anchoredReadsSupported(),
            "Anchored no-follow model reads unavailable on this filesystem; no successful model import or graph-control coverage is claimed")
    }

    @Test
    fun missingOrRetargetedIngressAuthorityIsRejectedBeforeAnyOwnerCallback() {
        val fixture = Fixture()
        val graph = fixture.graph()
        val request = fixture.request(graph)
        val raw = bytes(graph)
        val calls = AtomicInteger()
        val permit = ModelIngressPermit(request, Digests.sha256("TEST-ONLY Gradle runtime"),
            verifySnapshot = { calls.incrementAndGet() },
            verifyExecutedExport = { calls.incrementAndGet() },
            verifyArtifact = { calls.incrementAndGet() },
            verifyModelInputs = { calls.incrementAndGet() })
        assertEquals("model_ingress_not_authorized", assertFailsWith<ProjectModelException> {
            ProjectModelImporter().import(request, raw)
        }.code)
        val importer = ProjectModelImporter(ExactModelIngressAuthority(permit))
        for (retargeted in listOf(
            request.copy(configurationDigest = Digests.sha256("different-policy")),
            request.copy(namespace = namespace.copy(lineageId = "another")),
        )) {
            assertEquals("model_ingress_not_authorized", assertFailsWith<ProjectModelException> {
                importer.import(retargeted, raw)
            }.code)
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun actualAnchoredReadCapabilityControlsAdmissionWithoutFallback() {
        val fixture = Fixture()
        val graph = fixture.graph()
        val request = fixture.request(graph)
        val raw = bytes(graph)
        val calls = AtomicInteger()
        val permit = fixture.permit(request, graph) { assertEquals(graph, it); calls.incrementAndGet() }
        val importer = ProjectModelImporter(ExactModelIngressAuthority(permit))
        if (fixture.anchoredReadsSupported()) {
            assertEquals(ModelCompleteness.COMPLETE, importer.import(request, raw).context("a-main", policy).completeness)
            assertEquals(2, calls.get())
        } else {
            // No assumption here: exercise the real production fail-closed path. Ordinary
            // synthetic callback reads cannot substitute for anchored model input reads.
            assertEquals("model_secure_io_unavailable", assertFailsWith<ProjectModelException> {
                importer.import(request, raw)
            }.code)
            assertEquals(1, calls.get())
        }
        // A permit is consumed even when input admission fails; neither branch can replay.
        val beforeReplay = calls.get()
        assertEquals("model_ingress_mismatch_or_replay", assertFailsWith<ProjectModelException> {
            importer.import(request, raw)
        }.code)
        assertEquals(beforeReplay, calls.get())
    }

    @Test
    fun callerExportCannotGrantAuthorityAndPermitCannotRetargetOrReplay() {
        val fixture = anchoredFixture()
        val graph = fixture.graph()
        val request = fixture.request(graph)
        val raw = bytes(graph)
        assertEquals("model_ingress_not_authorized", assertFailsWith<ProjectModelException> {
            ProjectModelImporter().import(request, raw)
        }.code)
        val importer = ProjectModelImporter(ExactModelIngressAuthority(fixture.permit(request, graph)))
        assertEquals("model_ingress_not_authorized", assertFailsWith<ProjectModelException> {
            importer.import(request.copy(configurationDigest = Digests.sha256("different-policy")), raw)
        }.code)
        assertEquals("model_ingress_not_authorized", assertFailsWith<ProjectModelException> {
            importer.import(request.copy(namespace = namespace.copy(lineageId = "another")), raw)
        }.code)
        assertEquals(ModelCompleteness.COMPLETE, importer.import(request, raw).context("a-main", policy).completeness)
        assertEquals("model_ingress_mismatch_or_replay", assertFailsWith<ProjectModelException> { importer.import(request, raw) }.code)
    }

    @Test
    fun byteIdenticalFileMovingBetweenAcceptedMembershipsCannotProduceAnEmptyImpactPlan() {
        val fixture = anchoredFixture()
        val graph = fixture.graph()
        val before = fixture.admit(graph)
        val api = fixture.file("src/Api.kt")
        val moved = graph.copy(
            sourceSets = graph.sourceSets.map { when (it.id) {
                "a-set" -> it.copy(sources = emptyList())
                "b-set" -> it.copy(sources = it.sources + api)
                else -> it
            } },
            compilations = graph.compilations.map { when (it.id) {
                "a-main", "a-test" -> it.copy(sourceFiles = emptyList())
                "b-main" -> it.copy(sourceFiles = it.sourceFiles + api)
                else -> it
            } })
        val after = fixture.admit(moved)
        assertTrue(before.issues.isEmpty() && after.issues.isEmpty())
        assertEquals(before.snapshot, after.snapshot)
        assertNotEquals(before.identity.modelDigest, after.identity.modelDigest)
        val impact = ProjectImpactPlanner().impact(before, after)
        assertTrue(impact.changedFiles.isEmpty(), "Witness must not rely on a changed byte or build file")
        assertEquals(setOf("a-main", "a-test", "b-main", "c-main"), impact.obligations.map { it.compilationId }.toSet())
        assertTrue("model_topology_membership_or_arguments_changed" in impact.reasons)
    }

    @Test
    fun producersMustOwnTheSelectedModuleVariantAndAssociatedCompilation() {
        val fixture = anchoredFixture()
        val graph = fixture.graph()
        val consumer = graph.compilations.single { it.id == "c-main" }
        assertEquals(ModelCompleteness.COMPLETE, fixture.admit(graph).context("a-main", policy).completeness)
        for ((bad, expectedCode) in listOf(
            ModelProjectDependency(":a", "bApiElements", emptyMap(), "b-main") to "model_producer_variant_invalid",
            ModelProjectDependency(":a", "nonexistent-variant", emptyMap(), "a-main") to "model_producer_variant_invalid",
            ModelProjectDependency(":a", "aApiElements", emptyMap(), "missing-producer") to "model_reference_invalid",
        )) {
            assertEquals(expectedCode, assertFailsWith<ProjectModelException> {
                fixture.admit(graph.copy(compilations = graph.compilations.map {
                    if (it.id == consumer.id) it.copy(projectDependencies = listOf(bad)) else it
                }))
            }.code)
        }
        for (wrong in listOf("b-main", "missing", "a-test")) {
            assertEquals("model_reference_invalid", assertFailsWith<ProjectModelException> {
                fixture.admit(graph.copy(compilations = graph.compilations.map {
                    if (it.id == "a-test") it.copy(associatedCompilationIds = listOf(wrong)) else it
                }))
            }.code)
        }
    }

    @Test
    fun importerDeepFreezesNewAssociatedAndGeneratorCollections() {
        val fixture = anchoredFixture()
        val graph = fixture.graph()
        val model = fixture.admit(graph)
        val originalAssociated = graph.compilations.single { it.id == "a-test" }.associatedCompilationIds as MutableList<String>
        originalAssociated += "b-main"
        (graph.generatedInputs.single().inputProperties as MutableMap<String, String>)["fixture-mode"] = "changed"
        assertEquals(listOf("a-main"), model.compilation("a-test")!!.associatedCompilationIds)
        assertEquals("synthetic", model.export.generatedInputs.single().inputProperties["fixture-mode"])
        assertFailsWith<UnsupportedOperationException> {
            (model.compilation("a-test")!!.associatedCompilationIds as MutableList<String>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (model.export.generatedInputs.single().inputProperties as MutableMap<String, String>)["new"] = "mutation"
        }
        assertTrue(model.issues.isEmpty())
    }

    @Test
    fun unselectedUnknownEdgesRemainGraphGapsAndWidenAllRelevantObligations() {
        val fixture = anchoredFixture()
        val graph = fixture.graph()
        val before = fixture.admit(graph)
        val issue = ModelIssue("project_variant_unmapped", "b-main", "Synthetic unresolved dependency outside selected compilation")
        val after = fixture.admit(graph.copy(issues = listOf(issue)))
        assertTrue(issue in after.graphIssues(listOf("a-main")))
        val selected = after.context("a-main", policy)
        assertEquals(ModelCompleteness.UNAVAILABLE, selected.completeness)
        assertFalse(selected.context!!.fullProject)
        assertNull(selected.certificateId)
        val impact = ProjectImpactPlanner().impact(before, after)
        assertEquals(4, impact.obligations.size)
        assertTrue(impact.obligations.all { it.state == "unsupported" })
    }

    @Test
    fun removedCompilationIsRetainedAsAnUnresolvedConsumerNotSilentlyRetired() {
        val fixture = anchoredFixture()
        val graph = fixture.graph()
        val before = fixture.admit(graph)
        val after = fixture.admit(graph.copy(compilations = graph.compilations.filterNot { it.id == "c-main" }))
        val impact = ProjectImpactPlanner().impact(before, after)
        val removed = impact.obligations.single { it.compilationId == "c-main" }
        assertEquals("removed_unresolved", removed.state)
        assertEquals(":c", removed.context!!.module)
        assertTrue("consumer_removed_without_retirement_evidence" in removed.reasons)
        assertEquals(4, impact.obligations.size)
    }

    @Test
    fun completeCaptureAuthorityMustBeRecheckedRatherThanInferredFromAnEmptyExportGapList() {
        val fixture = anchoredFixture()
        val graph = fixture.graph()
        val calls = AtomicInteger()
        val acceptedRequest = fixture.request(graph)
        val acceptedPermit = fixture.permit(acceptedRequest, graph) { assertEquals(graph, it); calls.incrementAndGet() }
        val model = ProjectModelImporter(ExactModelIngressAuthority(acceptedPermit)).import(acceptedRequest, bytes(graph))
        assertTrue(model.issues.isEmpty())
        assertEquals(2, calls.get())
        calls.set(0)
        val deniedRequest = fixture.request(graph)
        val deniedPermit = fixture.permit(deniedRequest, graph) {
            if (calls.incrementAndGet() == 2) throw ProjectModelException("audit_capture_changed", "Synthetic complete-input authority revoked")
        }
        assertEquals("audit_capture_changed", assertFailsWith<ProjectModelException> {
            ProjectModelImporter(ExactModelIngressAuthority(deniedPermit)).import(deniedRequest, bytes(graph))
        }.code)
        assertEquals(2, calls.get())
    }

    @Test
    fun classpathOrderChangeInvalidatesDependencyIdentityEvenWhenSourceBytesAreIdentical() {
        val fixture = anchoredFixture()
        val graph = fixture.graph()
        val before = fixture.admit(graph)
        val after = fixture.admit(graph.copy(compilations = graph.compilations.map {
            if (it.id == "a-main") it.copy(classpath = it.classpath.reversed()) else it
        }))
        assertEquals(before.snapshot, after.snapshot)
        assertNotEquals(before.identity.dependencyDigest, after.identity.dependencyDigest)
        val impact = ProjectImpactPlanner().impact(before, after)
        assertTrue("resolved_dependency_identity_changed" in impact.reasons)
        assertEquals(4, impact.obligations.size)
    }

    @Test
    fun ordinaryOwnedByteChangeIncludesAssociatedAndReverseProjectConsumersWithoutLosingOldInputs() {
        val fixture = anchoredFixture()
        val before = fixture.admit()
        Files.writeString(fixture.root.resolve("src/Api.kt"), "// Different synthetic source bytes\n")
        val after = fixture.admit()
        val impact = ProjectImpactPlanner().impact(before, after)
        assertEquals(listOf("src/Api.kt"), impact.changedFiles)
        assertTrue(impact.reasons.isEmpty(), "Known owned byte change must not masquerade as a topology change")
        assertEquals(setOf("a-main", "a-test", "c-main"), impact.obligations.map { it.compilationId }.toSet())
        assertTrue(impact.obligations.all { it.state == "pending" })
    }

    @Test
    fun anotherLineageOrPolicyCannotReuseVerifiedModelAuthority() {
        val fixture = anchoredFixture()
        val before = fixture.admit()
        val other = fixture.admit(identity = namespace.copy(lineageId = "another-lineage"))
        assertEquals("impact_namespace_mismatch", assertFailsWith<ProjectModelException> {
            ProjectImpactPlanner().impact(before, other)
        }.code)
        assertEquals("model_configuration_mismatch", assertFailsWith<ProjectModelException> {
            before.context("a-main", Digests.sha256("not-the-frozen-policy"))
        }.code)
    }
}

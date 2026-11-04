package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.ClientBinding
import dev.ktchecker.kernel.SubmitPlanRequest
import dev.ktchecker.projectmodel.*
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.util.UUID
import kotlinx.serialization.encodeToString

/**
 * Unit scaffolding adapted from project-model's ModelFixtures: REAL registered overlays/files,
 * but SYNTHETIC owner export/approval callbacks and dummy artifact bytes, not executed Gradle,
 * a real compiler, runtime qualification, live-worktree authority or production owner ingress.
 */
internal class ControllerFixtures(val work: Path, val namespace: ModelNamespace = ModelNamespace("controller-owner", "workspace", "worktree", "lineage")) {
    val identity = SnapshotIdentity(namespace.ownerId, namespace.workspaceId, namespace.worktreeId)
    val store = SnapshotStore(work.resolve("snapshots"), identity.ownerId).also { it.registerWorktree(identity) }
    val configuration = Digests.sha256("synthetic-captured-owner-policy")
    val inventory = OwnerCatalogInventory(Digests.sha256("complete-unit-catalog"), listOf("unit/rule-b", "unit/rule-a"))
    val artifacts = listOf("z-first", "a-second").map { name ->
        val path = work.resolve("artifacts/$name.jar")
        Files.createDirectories(path.parent); Files.writeString(path, "unit artifact $name (not executable compiler code)")
        ModelArtifact("unit:$name:1", path.toString(), Digests.sha256(Files.readAllBytes(path)), Files.size(path))
    }
    private var previous: SnapshotCommit? = null
    private var previousFiles: Map<String, String> = emptyMap()

    fun binding(client: String = "client") = ClientBinding(namespace.ownerId, namespace.workspaceId, namespace.worktreeId, client,
        namespace.lineageId, sessionId = "session-$client")
    fun request(name: String = "one") = SubmitPlanRequest("patch-$name", "key-$name", "ref-$name")

    class Capture(val root: Path, val snapshot: SnapshotManifest, val verifyRegistered: () -> Boolean) {
        var epoch = Digests.sha256("registered-capture:${snapshot.snapshotId}")
        var available = true
        var verifies = 0

        /**
         * Deliberately perturb only this test fixture's existing sealed Common.kt. Same-owner
         * tampering requires temporarily changing its permissions on nonprivileged hosts;
         * UID 0 can otherwise mask that prerequisite. The registered CAS stays untouched and
         * sealed directories stay read-only; changed bytes must still fail production verification.
         */
        fun replaceCommonSourceForTest(text: String) {
            val path = root.resolve("lib/src/commonMain/kotlin/Common.kt")
            check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Test capture source is not a regular file" }
            val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            if (posix != null) {
                val original = posix.readAttributes().permissions().toSet()
                try {
                    posix.setPermissions(original + OWNER_WRITE)
                    Files.writeString(path, text, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS)
                } finally {
                    posix.setPermissions(original)
                }
            } else {
                val dos = checkNotNull(Files.getFileAttributeView(path, DosFileAttributeView::class.java, NOFOLLOW_LINKS)) {
                    "Test capture source needs supported read-only attributes"
                }
                val original = dos.readAttributes().isReadOnly
                try {
                    dos.setReadOnly(false)
                    Files.writeString(path, text, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS)
                } finally {
                    dos.setReadOnly(original)
                }
            }
        }

        fun materialization(model: VerifiedProjectModel, transform: (MaterializationVerification) -> MaterializationVerification = { it }): OwnerPlanMaterialization =
            OwnerPlanMaterialization(model, root) {
                verifies++
                if (!available || !verifyRegistered()) null else transform(MaterializationVerification(snapshot, root.toString(), epoch))
            }
    }

    fun capture(files: Map<String, String> = files()): Capture {
        val deletes = (previousFiles.keys - files.keys).sorted().map { SnapshotChange.Delete(SnapshotPath(path = it)) }
        val puts = files.entries.sortedBy { it.key }.map { (path, text) -> SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(text)) }
        val prior = previous
        val commit = store.sealOverlay(OverlayRequest(identity, "fixture-owner", "fixture-session", UUID.randomUUID().toString(),
            prior?.snapshotId, prior?.headRevision ?: 0, deletes + puts))
        previous = commit; previousFiles = files.toMap()
        val materialized = store.materialize(identity, commit.snapshotId, work.resolve("capture-${commit.headRevision}"))
        return Capture(materialized.root, materialized.manifest.toContractManifest()) { store.verifyMaterialization(materialized).valid }
    }

    fun graph(capture: Capture): GradleProjectExport {
        val files = capture.snapshot.files.associateBy { it.path }
        fun file(path: String) = files.getValue(path).let { ModelFile(it.path, it.digest, it.size) }
        fun sourceSet(module: String, name: String, parent: String? = null): ModelSourceSet {
            val root = "${module.removePrefix(":")}/src/$name"
            return ModelSourceSet("$module|$name", module, name, parent?.let { listOf("$module|$it") }.orEmpty(),
                listOf("$root/kotlin"), listOf("$root/resources"), files.keys.filter { it.startsWith("$root/kotlin/") }.sorted().map(::file),
                files.keys.filter { it.startsWith("$root/resources/") }.sorted().map(::file))
        }
        val sets = listOf(sourceSet(":lib", "commonMain"), sourceSet(":lib", "jvmMain", "commonMain"),
            sourceSet(":lib", "desktopMain", "commonMain"), sourceSet(":lib", "jvmTest"), sourceSet(":app", "jvmMain"), sourceSet(":independent", "jvmMain"))
        fun compilation(module: String, target: String, name: String = "main", sourceSets: List<String>, dependency: String? = null,
                        associated: List<String> = emptyList()): ModelCompilation {
            val ids = sourceSets.map { "$module|$it" }
            return ModelCompilation("$module|$target|$name", module, target, "jvm", name, ids.last(), ids,
                sets.filter { it.id in ids }.flatMap { it.sources }.distinctBy { it.path }.sortedBy { it.path },
                "$module:compile-$target-$name", "${target}${name}CompileClasspath", artifacts,
                dependency?.let { listOf(ModelProjectDependency(it, "jvmApiElements", mapOf("platform" to "jvm"), "$it|jvm|main")) }.orEmpty(),
                ModelToolchain("jvm", "2.4.21", listOf(artifacts.first()), listOf(artifacts.last())),
                listOf("-language-version", "2.4", "-api-version", "2.4"), "2.4", "2.4",
                producedVariants = listOf(if (target == "desktop") "desktopApiElements" else "jvmApiElements"), associatedCompilationIds = associated)
        }
        return GradleProjectExport(gradleVersion = "9.6.1", modules = listOf(":lib", ":app", ":independent").map {
            ModelModule(it, it.removePrefix(":"), "${it.removePrefix(":")}/build.gradle.kts", listOf("org.jetbrains.kotlin.multiplatform"))
        }, sourceSets = sets, compilations = listOf(
            compilation(":lib", "jvm", sourceSets = listOf("commonMain", "jvmMain")),
            compilation(":lib", "desktop", sourceSets = listOf("commonMain", "desktopMain")),
            compilation(":lib", "jvm", "test", listOf("jvmTest"), associated = listOf(":lib|jvm|main")),
            compilation(":app", "jvm", sourceSets = listOf("jvmMain"), dependency = ":lib"),
            compilation(":independent", "jvm", sourceSets = listOf("jvmMain"))),
            configurationInputs = files.keys.filter { it.endsWith(".gradle.kts") }.sorted().map(::file), generatedInputs = emptyList(),
            buildscriptArtifacts = listOf(artifacts.first()), includedBuilds = emptyList(), issues = emptyList())
    }

    fun import(capture: Capture, graph: GradleProjectExport = graph(capture), configuration: String = this.configuration): VerifiedProjectModel {
        val raw = ContractJson.encodeToString(graph).toByteArray()
        val expected = ContractJson.decodeFromString<GradleProjectExport>(raw.toString(Charsets.UTF_8))
        val request = ModelImportRequest(namespace, capture.snapshot, capture.root.toString(), configuration, Digests.sha256(raw),
            GradleModelExporter.scriptDigest(), UUID.randomUUID().toString())
        val permit = ModelIngressPermit(request, Digests.sha256("unit-owner-runtime"),
            { check(capture.available && capture.verifyRegistered()) { "Unit registered capture changed" } },
            { check(Digests.sha256(raw) == request.exportDigest) { "Unit owner receipt changed (not actual Gradle execution)" } },
            { selected -> check(selected in artifacts) { "Artifact is outside this unit owner's immutable registry" } },
            { selected -> check(selected == expected) { "Unit owner graph declaration changed" } })
        return ProjectModelImporter(ExactModelIngressAuthority(permit)).import(request, raw)
    }

    fun files(): Map<String, String> = mapOf(
        "settings.gradle.kts" to "rootProject.name = \"controller-fixture\"\n",
        "lib/build.gradle.kts" to "// unit lib model\n", "app/build.gradle.kts" to "// unit app model\n",
        "independent/build.gradle.kts" to "// unit independent model\n",
        "lib/src/commonMain/kotlin/Common.kt" to "fun commonValue() = 1\n",
        "lib/src/commonMain/resources/message.txt" to "captured resource\n",
        "lib/src/jvmMain/kotlin/Jvm.kt" to "fun jvmValue() = commonValue()\n",
        "lib/src/desktopMain/kotlin/Desktop.kt" to "fun desktopValue() = commonValue()\n",
        "lib/src/jvmTest/kotlin/Check.kt" to "fun checkValue() = jvmValue()\n",
        "app/src/jvmMain/kotlin/App.kt" to "fun appValue() = jvmValue()\n",
        "independent/src/jvmMain/kotlin/Independent.kt" to "fun independentValue() = 0\n",
    )
}

internal class TestPreparations {
    var policyRevision = Digests.sha256("unit-policy-revision")
    var executionApproval = Digests.sha256("unit-execution-approval")
    val policyRequests = mutableListOf<PolicyPreparationRequest>()
    val executionRequests = mutableListOf<ExecutionPreparationRequest>()
    val policyRecords = mutableMapOf<Pair<ClientBinding, String>, CapturedPolicyIdentity>()
    val executionRecords = mutableMapOf<Pair<ClientBinding, String>, CapturedExecutionIdentity>()
    var afterPolicyCapture: () -> Unit = {}
    var afterPolicyRetain: () -> Unit = {}
    var afterExecutionRetain: () -> Unit = {}

    fun policy(request: PolicyPreparationRequest): CapturedPlanPolicy {
        policyRequests += request
        val identity = CapturedPolicyIdentity(request.binding, request.input.context.configurationDigest!!, policyRevision)
        val activation = PlanActivation(identity.configurationDigest, request.input.requiredRuleIds.map {
            PlanRuleActivation(it, false, "unit_captured_policy", "experimental_rules_not_qualified")
        })
        val input = request.withActivation(activation)
        afterPolicyCapture()
        return CapturedPlanPolicy(request, identity, input) { final ->
            afterPolicyRetain()
            identity.copy(revisionDigest = policyRevision).also { policyRecords[request.binding to final.analysisKey()] = it }
        }
    }

    fun execution(request: ExecutionPreparationRequest): CapturedPlanExecution {
        executionRequests += request
        val identity = CapturedExecutionIdentity(request.binding, request.input.analysisKey(), request.input.context.toolchainDigest!!, executionApproval)
        val profiles = mapOf("unit-provider" to TrustedExecution("/unit-owner/no-process-is-started", listOf("--unit-profile"), approved = true,
            executableDigest = Digests.sha256("synthetic-launcher"), environment = mapOf("UNIT" to "fixture")))
        return CapturedPlanExecution(request, identity, profiles, request.withProfiles(profiles)) { final ->
            afterExecutionRetain()
            identity.copy(approvalDigest = executionApproval).also { executionRecords[request.binding to final.analysisKey()] = it }
        }
    }

    fun assembler(fixture: ControllerFixtures) = OwnerPlanAssembler(fixture.inventory, OwnerPolicyPreparer(::policy), OwnerExecutionPreparer(::execution))
}

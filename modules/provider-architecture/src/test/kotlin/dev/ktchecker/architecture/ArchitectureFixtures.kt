package dev.ktchecker.architecture

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.snapshot.*
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream

internal class ArchitectureFixtures(val modules: List<String> = listOf(":billing", ":pricing", ":catalog")) {
    // Only the fresh trusted outer root is canonicalized; model descendants stay no-follow.
    val work = Files.createTempDirectory("ktchecker-owner-graph-").toRealPath()
    val namespace = ModelNamespace("graph-owner", "graph-workspace", "graph-worktree", "graph-lineage")
    val configuration = Digests.sha256("explicit-owner-selected-policy-revision")
    val compilations = modules.map { "$it|jvm|main" }
    val materialized = run {
        val identity = SnapshotIdentity(namespace.ownerId, namespace.workspaceId, namespace.worktreeId)
        val store = SnapshotStore(work.resolve("cas"), identity.ownerId); store.registerWorktree(identity)
        val sourceFiles = listOf("settings.gradle") + modules.flatMap { listOf("${it.removePrefix(":")}/build.gradle", "${it.removePrefix(":")}/src/Main.kt") }
        val commit = store.sealOverlay(OverlayRequest(identity, "graph-fixture-owner", "graph-fixture-session", "fixture-patch", null, 0,
            sourceFiles.map { SnapshotChange.PutFile(SnapshotPath(path = it), ImmutableBlob.utf8("// exact producer fixture input $it\n")) }))
        val result = store.materialize(identity, commit.snapshotId, work.resolve("snapshot"))
        result to { check(store.verifyMaterialization(result).valid) }
    }
    val snapshot = materialized.first.manifest.toContractManifest()
    val artifact = work.resolve("selected.jar").let { path ->
        Files.writeString(path, "owner-selected test artifact")
        ModelArtifact("owner-fixture:artifact:1", path.toString(), Digests.sha256(Files.readAllBytes(path)), Files.size(path))
    }

    fun model(edges: List<PolicyEdge> = emptyList(), issues: List<ModelIssue> = emptyList(), refine: Boolean = false): VerifiedProjectModel {
        assumeTrue(Files.newDirectoryStream(work.root).use { it is SecureDirectoryStream<*> },
            "Anchored no-follow model reads unavailable; no verified-model architecture execution coverage is claimed")
        fun file(path: String) = snapshot.files.single { it.path == path }.let { ModelFile(it.path, it.digest, it.size) }
        val sets = modules.flatMap { module ->
            val root = module.removePrefix(":")
            val main = ModelSourceSet("$module|main", module, "main", if (refine) listOf("$module|common") else emptyList(),
                listOf("$root/src"), emptyList(), listOf(file("$root/src/Main.kt")), emptyList())
            if (refine) listOf(ModelSourceSet("$module|common", module, "common", emptyList(), listOf("$root/common"), emptyList(), emptyList(), emptyList()), main) else listOf(main)
        }
        val graph = GradleProjectExport(gradleVersion = "9.6.1", modules = modules.map { ModelModule(it, it.removePrefix(":"), "${it.removePrefix(":")}/build.gradle", listOf("org.jetbrains.kotlin.jvm")) },
            sourceSets = sets, compilations = modules.map { module ->
                ModelCompilation("$module|jvm|main", module, "jvm", "jvm", "main", "$module|main", sets.filter { it.moduleId == module }.map { it.id },
                    listOf(file("${module.removePrefix(":")}/src/Main.kt")), "$module:compileKotlin", "compileClasspath", listOf(artifact),
                    edges.filter { it.from == module }.map { ModelProjectDependency(it.to, "jvmApiElements", mapOf("platform" to "jvm"), "${it.to}|jvm|main") },
                    ModelToolchain("jvm", "2.4.21", listOf(artifact), listOf(artifact)), emptyList(), "2.4", "2.4", producedVariants = listOf("jvmApiElements"))
            }, configurationInputs = snapshot.files.filter { it.path.endsWith(".gradle") }.map { ModelFile(it.path, it.digest, it.size) },
            generatedInputs = emptyList(), buildscriptArtifacts = listOf(artifact), includedBuilds = emptyList(), issues = issues)
        val bytes = ContractJson.encodeToString(graph).toByteArray()
        val request = ModelImportRequest(namespace, snapshot, materialized.first.root.toString(), configuration, Digests.sha256(bytes), GradleModelExporter.scriptDigest(), "fixture-executed-export")
        val permit = ModelIngressPermit(request, Digests.sha256("fixture-selected-runtime"), materialized.second, {}, {
            check(it == artifact) { "Unknown owner artifact" }
        }, {})
        return ProjectModelImporter(ExactModelIngressAuthority(permit)).import(request, bytes)
    }

    fun scope(kind: ArchitectureGraphKind = ArchitectureGraphKind.GRADLE_MODULE_DEPENDENCY, traversal: ArchitectureEdgeTraversal = ArchitectureEdgeTraversal.DIRECT,
              self: Boolean = true): ArchitectureScope = ArchitectureScope(kind, compilations, modules.associateWith { it.removePrefix(":") }, traversal, self)

    fun bundle(vararg policies: DeclaredArchitecturePolicy) = ArchitecturePolicyBundle(namespace, configuration, 1, policies.toList())

    fun policies(model: VerifiedProjectModel, bundle: ArchitecturePolicyBundle): VerifiedArchitecturePolicies {
        val request = ArchitecturePolicyImporter.request(model, bundle)
        return ArchitecturePolicyImporter(ExactArchitecturePolicyAuthority(ArchitecturePolicyPermit(request, {}))).import(model, bundle)
    }

    fun input(model: VerifiedProjectModel, requested: List<String>, context: RuleContext? = null): AnalysisInput {
        val scope = context ?: RuleContext("jvm", "main", ":billing", "main")
        return AnalysisInput(snapshot, materialized.first.root.toString(), requireNotNull(model.context(scope, configuration).context), requested)
    }

    fun analyze(model: VerifiedProjectModel, bundle: ArchitecturePolicyBundle, requested: List<String>): ProviderResult =
        ArchitecturePolicyProvider(model, policies(model, bundle)).analyze(input(model, requested))
}

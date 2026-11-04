package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.semantic.*
import dev.ktchecker.snapshot.*
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.util.Comparator

/**
 * SYNTHETIC controls only: artifacts are text, facts and execution/ownership/policy receipts are
 * deliberately fake test inputs. No compiler, provider subprocess, application or JVM launch.
 * Model ingress/source coordinates exercise real validators on owned temporary snapshot bytes.
 */
internal class LibraryRuleFixtures(
    val text: String = "suspend fun owner() { runBlocking { 1 } }\n",
) : AutoCloseable {
    val work: Path = Files.createTempDirectory("ktchecker-library-rule-control-").toRealPath()
    init {
        val anchored = Files.newDirectoryStream(work.root).use { it is SecureDirectoryStream<*> }
        if (!anchored) Files.deleteIfExists(work)
        assumeTrue(anchored, "Anchored no-follow model ingress unavailable; not verified-model or Windows coverage.")
    }
    val namespace = ModelNamespace("library-test-owner", "library-test-workspace", "library-test-worktree", "library-test-lineage")
    val configuration = Digests.sha256("synthetic-owner-policy")
    val sourceSetId = ":library|main"
    val compilationId = ":library|jvm|main"
    private val sourcePath = "library/src/Main.kt"
    val sourceBytes = text.toByteArray(Charsets.UTF_8)
    private val identity = SnapshotIdentity(namespace.ownerId, namespace.workspaceId, namespace.worktreeId)
    private val store = SnapshotStore(work.resolve("cas"), identity.ownerId)
    private val materialized = run {
        store.registerWorktree(identity)
        val commit = store.sealOverlay(OverlayRequest(identity, "library-fixture-client", "library-fixture-session",
            "library-fixture-patch", null, 0, listOf(
                SnapshotChange.PutFile(SnapshotPath(path = "library/build.gradle"), ImmutableBlob.utf8("// synthetic build capture\n")),
                SnapshotChange.PutFile(SnapshotPath(path = sourcePath), ImmutableBlob.copyOf(sourceBytes)),
            )))
        store.materialize(identity, commit.snapshotId, work.resolve("snapshot"))
    }
    val snapshot = materialized.manifest.toContractManifest()
    val root: String = materialized.root.toString()
    val source = SemanticSource(sourcePath, Digests.sha256(sourceBytes), sourceBytes.size.toLong(), sourceSetId)
    private val coordinates = SourceCoordinates(source, sourceBytes)
    val compiler = artifact("compiler.jar", "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
    val coroutines = artifact("coroutines.jar", RUNBLOCKING_ARTIFACT)
    val stdlib = artifact("stdlib.jar", "org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    val plugin = artifact("plugin.jar", "synthetic-fixture:semantic-plugin:2.4.21")
    val runtime = artifact("runtime.jar", "synthetic-fixture:jdk-runtime:21")
    private val knownArtifacts = listOf(compiler, coroutines, stdlib, plugin, runtime)

    private fun artifact(filename: String, coordinate: String): ArtifactIdentity {
        val path = work.resolve(filename)
        Files.writeString(path, "SYNTHETIC non-JAR bytes for $coordinate\n")
        return ArtifactIdentity(coordinate, Digests.sha256(Files.readAllBytes(path)), path.toString())
    }

    fun model(issues: List<ModelIssue> = emptyList(), target: String = "jvm", platform: String = "jvm",
              compilerVersion: String = SEMANTIC_COMPILER_VERSION,
              dependencies: List<ArtifactIdentity> = listOf(coroutines, stdlib)): VerifiedProjectModel {
        fun modelArtifact(value: ArtifactIdentity) =
            ModelArtifact(value.coordinate, value.path, value.digest, Files.size(Path.of(value.path)))
        fun modelFile(path: String) = snapshot.files.single { it.path == path }.let { ModelFile(it.path, it.digest, it.size) }
        val file = modelFile(sourcePath)
        val graph = GradleProjectExport(gradleVersion = "9.6.1",
            modules = listOf(ModelModule(":library", "library", "library/build.gradle", listOf("org.jetbrains.kotlin.jvm"))),
            sourceSets = listOf(ModelSourceSet(sourceSetId, ":library", "main", emptyList(),
                listOf("library/src"), emptyList(), listOf(file), emptyList())),
            compilations = listOf(ModelCompilation(compilationId, ":library", target, platform, "main", sourceSetId,
                listOf(sourceSetId), listOf(file), ":library:compileKotlin", "compileClasspath",
                dependencies.map(::modelArtifact), emptyList(),
                ModelToolchain(platform, compilerVersion, listOf(modelArtifact(compiler)), listOf(modelArtifact(runtime)),
                    listOf(modelArtifact(plugin))),
                listOf("-language-version", "2.4", "-api-version", "2.4"), "2.4", "2.4",
                producedVariants = listOf("jvmApiElements"))),
            configurationInputs = listOf(modelFile("library/build.gradle")), generatedInputs = emptyList(),
            buildscriptArtifacts = listOf(modelArtifact(compiler)), includedBuilds = emptyList(), issues = issues)
        val exportBytes = ContractJson.encodeToString(graph).toByteArray()
        val request = ModelImportRequest(namespace, snapshot, root, configuration, Digests.sha256(exportBytes),
            GradleModelExporter.scriptDigest(), "SYNTHETIC-model-execution")
        val permit = ModelIngressPermit(request, Digests.sha256("SYNTHETIC-gradle-runtime"),
            { check(store.verifyMaterialization(materialized).valid) }, {}, { actual ->
                check(actual in knownArtifacts.map(::modelArtifact))
                check(Digests.sha256(Files.readAllBytes(Path.of(actual.path))) == actual.digest)
            }, {})
        return ProjectModelImporter(ExactModelIngressAuthority(permit)).import(request, exportBytes)
    }

    fun input(model: VerifiedProjectModel, rules: List<String> = listOf(RUNBLOCKING_IN_SUSPEND)): AnalysisInput =
        AnalysisInput(snapshot, root, requireNotNull(model.context(compilationId, configuration).context), rules)

    fun span(fragment: String, occurrence: Int = 0): SemanticSpan {
        var start = -1
        repeat(occurrence + 1) { start = text.indexOf(fragment, start + 1) }
        check(start >= 0) { "Absent synthetic source fragment" }
        return span(start, start + fragment.length)
    }

    fun span(start: Int, end: Int): SemanticSpan {
        fun point(offset: Int): Pair<Int, Int> {
            val prefix = text.substring(0, offset)
            return prefix.count { it == '\n' } to (offset - (prefix.lastIndexOf('\n') + 1))
        }
        val (line, column) = point(start)
        val (endLine, endColumn) = point(end)
        return coordinates.span(line, column, endLine, endColumn)
    }

    fun owner(name: String = "owner", kind: String = "function", suspend: Boolean = true,
              source: SemanticSpan = span(0, text.trimEnd().length), local: Boolean = false): SymbolFact {
        val descriptor = SymbolFact("", kind, if (local) "<local:$name>" else "fixture.$name",
            "SYNTHETIC-source-module", "Source", local, source, returnType = plainClass("kotlin.Unit"),
            flags = listOf("visibility:public") + if (suspend) listOf("suspend") else emptyList())
        return descriptor.copy(id = resolvedSymbolIdentity(descriptor))
    }

    fun target(transform: (SymbolFact) -> SymbolFact = { it }): SymbolFact {
        val result = TypeFact("type_parameter", nullable = false,
            parameterOwner = Digests.sha256("SYNTHETIC-extractor-ownerKey-not-symbolId"), parameterIndex = 0)
        val block = TypeFact("class", "kotlin.coroutines.SuspendFunction1", false, listOf(
            TypeProjectionFact("invariant", plainClass("kotlinx.coroutines.CoroutineScope")),
            TypeProjectionFact("invariant", result),
        ))
        val descriptor = transform(SymbolFact("", "function", "kotlinx.coroutines.runBlocking",
            "SYNTHETIC-coroutines-binary-module", "Library", false, returnType = result,
            parameters = listOf(SemanticParameter(0, "context", plainClass("kotlin.coroutines.CoroutineContext")),
                SemanticParameter(1, "block", block)), typeParameterCount = 1, flags = listOf("visibility:public")))
        return descriptor.copy(id = resolvedSymbolIdentity(descriptor))
    }

    fun call(target: SymbolFact, owner: SymbolFact?, fragment: String = "runBlocking { 1 }",
             kind: String = "function_call"): CallFact {
        val expression = span(fragment)
        return CallFact(expression, kind, target.id, owner?.id, plainClass("kotlin.Int"),
            arguments = if (kind == "function_call") listOf(ArgumentBindingFact(expression, 1,
                requireNotNull(target.parameters.getOrNull(1)).name)) else emptyList())
    }

    fun selection(model: VerifiedProjectModel, owners: List<SymbolFact> = listOf(owner()),
                  targets: List<SymbolFact> = listOf(target()),
                  calls: List<CallFact> = listOf(call(targets.first(), owners.first())),
                  exemptions: List<BlockingInteropExemption> = emptyList()): RunBlockingFactSelection {
        val context = input(model).context
        val request = SemanticFactsRequest(snapshot.snapshotId, Digests.sha256("SYNTHETIC-extraction-session"),
            requireNotNull(context.toolchainDigest), listOf(compiler), plugin, root, listOf(source),
            listOf(SemanticSourceSet(sourceSetId)), sourceSetId, model.compilation(compilationId)!!.target,
            compilationId, context.compilerArguments, context.classpath)
        val shard = SemanticFactShard(request.digest(), request.snapshotId, request.analysisId, request.toolchainId,
            request.pluginArtifact.digest, SEMANTIC_COMPILER_VERSION, source,
            CompilerSourceContext("SYNTHETIC-source-module", "JVM", false, emptyList()),
            owners.map { it.id }, owners + targets, calls, emptyList(), emptyList(), emptyList())
        return RunBlockingFactSelection(request, listOf(shard), 0,
            RunBlockingApplicability(coroutines, targets.map { it.id }, exemptions))
    }

    class Receipts {
        var snapshots = 0
        var extractions = 0
        var artifacts = 0
        var policies = 0
        var reads = 0
        val ownedSymbols = mutableListOf<String>()
    }

    /** Deliberately fake execution/ownership/policy receipts confined to this test source. */
    fun syntheticPermit(model: VerifiedProjectModel, request: LibraryFactAuthorizationRequest,
                        receipts: Receipts = Receipts(),
                        afterSnapshot: () -> Unit = {}, afterExtraction: () -> Unit = {},
                        afterArtifact: () -> Unit = {}, afterOwnership: () -> Unit = {},
                        afterPolicy: () -> Unit = {},
                        bytes: () -> ByteArray = { sourceBytes.copyOf() }): LibraryFactPermit =
        LibraryFactPermit(model, request,
            verifySnapshot = {
                receipts.snapshots++
                check(it.input.snapshot == snapshot && store.verifyMaterialization(materialized).valid)
                afterSnapshot()
            },
            verifyExtraction = {
                receipts.extractions++
                check(it == request) // SYNTHETIC registry, never real compiler-execution evidence.
                afterExtraction()
            },
            verifyArtifact = {
                receipts.artifacts++
                check(it in knownArtifacts && Digests.sha256(Files.readAllBytes(Path.of(it.path))) == it.digest)
                afterArtifact()
            },
            verifyCallableOwnership = { symbol, artifact, actual ->
                receipts.ownedSymbols += symbol
                check(artifact == coroutines && actual.semanticRequest.dependencyArtifacts == request.semanticRequest.dependencyArtifacts)
                check(symbol in request.applicability.selectedSymbolIds) // SYNTHETIC ownership only.
                afterOwnership()
            },
            verifyInteropPolicy = {
                receipts.policies++
                check(it.applicability == request.applicability) // SYNTHETIC policy selection only.
                afterPolicy()
            },
            readSourceBytes = {
                receipts.reads++
                check(it == source)
                bytes()
            })

    fun analyze(model: VerifiedProjectModel, selection: RunBlockingFactSelection,
                input: AnalysisInput = input(model), receipts: Receipts = Receipts(),
                cancellation: CancellationToken = CancellationToken.NONE): ProviderResult {
        val reviewed = RunBlockingInSuspendProvider(model, selection).authorizationRequest(input)
        val permit = syntheticPermit(model, reviewed, receipts)
        return RunBlockingInSuspendProvider(model, selection, ExactLibraryFactAuthority(permit)).analyze(input, cancellation)
    }

    override fun close() {
        // Owned temporary bytes only, after analysis. Restore directory writes before deletion.
        Files.walk(work).use { paths -> paths.forEach { it.toFile().setWritable(true, true) } }
        Files.walk(work).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    companion object {
        fun plainClass(classifier: String) = TypeFact("class", classifier, false)
    }
}

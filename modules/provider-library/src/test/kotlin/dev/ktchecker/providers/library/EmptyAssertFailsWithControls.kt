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
 * SYNTHETIC provider controls only. All compiler facts, model/execution/ownership/test-policy
 * receipts are deliberately fabricated; artifact files contain non-JAR text. Real validators
 * operate on real owned immutable snapshot bytes, but this is NOT real FIR/provenance evidence.
 * See the separate real kotlin-test API contract tests and prospective compiler fixture sources.
 */
internal class EmptyAssertFailsWithControls(
    val text: String = "@Test fun missingAction() { assertFailsWith<IllegalArgumentException> { } }\n",
) : AutoCloseable {
    val work: Path = Files.createTempDirectory("ktchecker-empty-assertion-control-").toRealPath()
    init {
        val anchored = Files.newDirectoryStream(work.root).use { it is SecureDirectoryStream<*> }
        if (!anchored) Files.deleteIfExists(work)
        assumeTrue(anchored, "No anchored model ingress; not verified-model or Windows coverage.")
    }
    val namespace = ModelNamespace("empty-test-owner", "empty-test-workspace", "empty-test-worktree", "empty-test-lineage")
    val configuration = Digests.sha256("SYNTHETIC explicit required-pass test policy")
    val sourceSetId = ":testing|test"
    val compilationId = ":testing|jvm|test"
    private val sourcePath = "testing/src/Test.kt"
    val sourceBytes = text.toByteArray(Charsets.UTF_8)
    private val identity = SnapshotIdentity(namespace.ownerId, namespace.workspaceId, namespace.worktreeId)
    private val store = SnapshotStore(work.resolve("cas"), identity.ownerId)
    private val materialized = run {
        store.registerWorktree(identity)
        val commit = store.sealOverlay(OverlayRequest(identity, "empty-client", "empty-session", "empty-patch", null, 0, listOf(
            SnapshotChange.PutFile(SnapshotPath(path = "testing/build.gradle"), ImmutableBlob.utf8("// SYNTHETIC model\n")),
            SnapshotChange.PutFile(SnapshotPath(path = sourcePath), ImmutableBlob.copyOf(sourceBytes)),
        )))
        store.materialize(identity, commit.snapshotId, work.resolve("snapshot"))
    }
    val snapshot = materialized.manifest.toContractManifest()
    val root = materialized.root.toString()
    val source = SemanticSource(sourcePath, Digests.sha256(sourceBytes), sourceBytes.size.toLong(), sourceSetId)
    private val coordinates = SourceCoordinates(source, sourceBytes)
    val compiler = artifact("compiler.jar", "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
    val kotlinTest = artifact("kotlin-test.jar", EMPTY_ASSERTION_KOTLIN_TEST)
    val stdlib = artifact("stdlib.jar", "org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    val plugin = artifact("plugin.jar", "synthetic-empty:semantic-plugin:2.4.21")
    val runtime = artifact("runtime.jar", "synthetic-empty:jdk-runtime:21")
    private val artifacts = listOf(compiler, kotlinTest, stdlib, plugin, runtime)
    val trace = mutableListOf<String>()

    private fun artifact(filename: String, coordinate: String): ArtifactIdentity {
        val path = work.resolve(filename)
        Files.writeString(path, "SYNTHETIC non-JAR fixture bytes for $coordinate\n")
        return ArtifactIdentity(coordinate, Digests.sha256(Files.readAllBytes(path)), path.toString())
    }

    fun model(target: String = "jvm", platform: String = "jvm", compilerVersion: String = SEMANTIC_COMPILER_VERSION,
              dependencies: List<ArtifactIdentity> = listOf(kotlinTest, stdlib)): VerifiedProjectModel {
        fun artifact(value: ArtifactIdentity) = ModelArtifact(value.coordinate, value.path, value.digest, Files.size(Path.of(value.path)))
        fun file(path: String) = snapshot.files.single { it.path == path }.let { ModelFile(it.path, it.digest, it.size) }
        val sourceFile = file(sourcePath)
        val graph = GradleProjectExport(gradleVersion = "9.6.1",
            modules = listOf(ModelModule(":testing", "testing", "testing/build.gradle", listOf("org.jetbrains.kotlin.jvm"))),
            sourceSets = listOf(ModelSourceSet(sourceSetId, ":testing", "test", emptyList(), listOf("testing/src"), emptyList(),
                listOf(sourceFile), emptyList())),
            compilations = listOf(ModelCompilation(compilationId, ":testing", target, platform, "test", sourceSetId,
                listOf(sourceSetId), listOf(sourceFile), ":testing:compileTestKotlin", "testCompileClasspath",
                dependencies.map(::artifact), emptyList(), ModelToolchain(platform, compilerVersion, listOf(artifact(compiler)),
                    listOf(artifact(runtime)), listOf(artifact(plugin))), listOf("-language-version", "2.4", "-api-version", "2.4"), "2.4", "2.4")),
            configurationInputs = listOf(file("testing/build.gradle")), generatedInputs = emptyList(),
            buildscriptArtifacts = listOf(artifact(compiler)), includedBuilds = emptyList(), issues = emptyList())
        val bytes = ContractJson.encodeToString(graph).toByteArray()
        val request = ModelImportRequest(namespace, snapshot, root, configuration, Digests.sha256(bytes),
            GradleModelExporter.scriptDigest(), "SYNTHETIC model export")
        val permit = ModelIngressPermit(request, Digests.sha256("SYNTHETIC gradle runtime"),
            { check(store.verifyMaterialization(materialized).valid) }, {}, { actual ->
                check(actual in artifacts.map(::artifact))
                check(Digests.sha256(Files.readAllBytes(Path.of(actual.path))) == actual.digest)
            }, {})
        return ProjectModelImporter(ExactModelIngressAuthority(permit)).import(request, bytes)
    }

    fun input(model: VerifiedProjectModel, rules: List<String> = listOf(EMPTY_ASSERT_FAILS_WITH)): AnalysisInput =
        AnalysisInput(snapshot, root, requireNotNull(model.context(compilationId, configuration).context), rules)

    fun span(fragment: String, occurrence: Int = 0): SemanticSpan {
        var start = -1
        repeat(occurrence + 1) { start = text.indexOf(fragment, start + 1) }
        check(start >= 0) { "Missing synthetic source fragment" }
        return span(start, start + fragment.length)
    }

    fun span(start: Int, end: Int): SemanticSpan {
        fun point(offset: Int): Pair<Int, Int> {
            var at = 0
            var line = 0
            var beginning = 0
            while (at < offset) {
                if (text[at] == '\r') {
                    if (text.getOrNull(at + 1) == '\n') at++
                    beginning = at + 1; line++
                } else if (text[at] == '\n') { beginning = at + 1; line++ }
                at++
            }
            return line to (offset - beginning)
        }
        val (line, column) = point(start)
        val (endLine, endColumn) = point(end)
        return coordinates.span(line, column, endLine, endColumn)
    }

    fun bind(symbol: SymbolFact): SymbolFact = symbol.copy(id = resolvedSymbolIdentity(symbol))
    fun owner(name: String = "missingAction", source: SemanticSpan = span(0, text.trimEnd().length)): SymbolFact =
        bind(SymbolFact("", "function", "synthetic.empty.$name", "SYNTHETIC source", "Source", false, source,
            returnType = plain("kotlin.Unit"), flags = listOf("visibility:public"), annotationClassIds = listOf("org.junit.jupiter.api.Test")))
    fun lambda(source: SemanticSpan): SymbolFact = bind(SymbolFact("", "lambda", "<local:empty>", "SYNTHETIC source", "Source", true,
        source, returnType = plain("kotlin.Unit"), flags = listOf("visibility:local")))
    fun target(transform: (SymbolFact) -> SymbolFact = { it }): SymbolFact {
        val result = TypeFact("type_parameter", nullable = false, parameterOwner = Digests.sha256("SYNTHETIC ownerKey"), parameterIndex = 0)
        return bind(transform(SymbolFact("", "function", "kotlin.test.assertFailsWith", "SYNTHETIC kotlin-test binary", "Library", false,
            returnType = result, parameters = listOf(SemanticParameter(0, "message", plain("kotlin.String", true)),
                SemanticParameter(1, "block", TypeFact("class", "kotlin.Function0", false,
                    listOf(TypeProjectionFact("invariant", plain("kotlin.Any", true)))))),
            typeParameterCount = 1, flags = listOf("visibility:public", "inline"))))
    }
    fun entry(owner: SymbolFact, disposition: EmptyAssertionTestDisposition = EmptyAssertionTestDisposition.REQUIRED_TO_PASS) =
        EmptyAssertionTestEntry(owner.id, requireNotNull(owner.source), disposition)
    fun call(target: SymbolFact, owner: SymbolFact?, source: SemanticSpan, block: SemanticSpan) =
        CallFact(source, "function_call", target.id, owner?.id, plain("java.lang.IllegalArgumentException"),
            arguments = listOf(ArgumentBindingFact(block, 1, "block")))

    data class Scenario(val owner: SymbolFact, val block: SymbolFact, val target: SymbolFact, val call: CallFact,
                        val selection: EmptyAssertionFactSelection)
    fun scenario(model: VerifiedProjectModel, callText: String = "assertFailsWith<IllegalArgumentException> { }",
                 blockText: String = "{ }"): Scenario {
        val owner = owner()
        val block = lambda(span(blockText))
        val target = target()
        val call = call(target, owner, span(callText), span(blockText))
        return Scenario(owner, block, target, call, selection(model, listOf(owner, block), listOf(target), listOf(call),
            listOf(target.id), listOf(entry(owner))))
    }

    fun selection(model: VerifiedProjectModel, declarations: List<SymbolFact>, targets: List<SymbolFact>, calls: List<CallFact>,
                  selected: List<String>, entries: List<EmptyAssertionTestEntry>): EmptyAssertionFactSelection {
        val context = input(model).context
        val request = SemanticFactsRequest(snapshot.snapshotId, Digests.sha256("SYNTHETIC empty extraction"),
            requireNotNull(context.toolchainDigest), listOf(compiler), plugin, root, listOf(source),
            listOf(SemanticSourceSet(sourceSetId)), sourceSetId, requireNotNull(model.compilation(compilationId)).target,
            compilationId, context.compilerArguments, context.classpath)
        val shard = SemanticFactShard(request.digest(), request.snapshotId, request.analysisId, request.toolchainId,
            request.pluginArtifact.digest, SEMANTIC_COMPILER_VERSION, source,
            CompilerSourceContext("SYNTHETIC source", "JVM", false, emptyList()), declarations.map { it.id },
            declarations + targets, calls, emptyList(), emptyList(), emptyList())
        return EmptyAssertionFactSelection(request, listOf(shard), 0, EmptyAssertionApplicability(kotlinTest, selected, entries))
    }

    /** Every receipt remains SYNTHETIC even though source/model validators and hashes are real. */
    fun permit(model: VerifiedProjectModel, request: EmptyAssertionAuthorizationRequest, failAt: String? = null,
               stageHook: (String) -> Unit = {}, bytes: () -> ByteArray = { sourceBytes.copyOf() }): EmptyAssertionPermit {
        fun mark(stage: String) {
            trace += stage
            stageHook(stage)
            if (stage == failAt) error("SYNTHETIC confidential callback detail")
        }
        return EmptyAssertionPermit(model, request,
            verifySnapshot = { mark("snapshot"); check(it.input.snapshot == snapshot && store.verifyMaterialization(materialized).valid) },
            verifyExtraction = { mark("extraction"); check(it == request) }, // Fake execution registry.
            verifyArtifact = { actual ->
                mark("artifact"); check(actual in artifacts)
                check(Digests.sha256(Files.readAllBytes(Path.of(actual.path))) == actual.digest)
            },
            verifyCallableOwnership = { symbol, artifact, actual ->
                mark("ownership"); check(symbol in actual.applicability.selectedSymbolIds && artifact == kotlinTest)
            }, // Fake binary provenance registry; never a production recipe.
            verifyTestPolicy = { mark("policy"); check(it.applicability.testEntries == request.applicability.testEntries) },
            readSourceBytes = { mark("read"); check(it == source); bytes() })
    }

    fun analyze(model: VerifiedProjectModel, selection: EmptyAssertionFactSelection, input: AnalysisInput = input(model),
                failAt: String? = null, cancellation: CancellationToken = CancellationToken.NONE,
                stageHook: (String) -> Unit = {}, bytes: () -> ByteArray = { sourceBytes.copyOf() }): ProviderResult =
        EmptyAssertFailsWithProvider(model, selection, EmptyAssertionAuthority { request ->
            permit(model, request, failAt, stageHook, bytes)
        }).analyze(input, cancellation)

    override fun close() {
        Files.walk(work).use { paths -> paths.forEach { it.toFile().setWritable(true, true) } }
        Files.walk(work).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    companion object {
        fun plain(classifier: String, nullable: Boolean = false) = TypeFact("class", classifier, nullable)
    }
}

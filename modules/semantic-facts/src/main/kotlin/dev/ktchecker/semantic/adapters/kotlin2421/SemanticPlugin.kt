@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package dev.ktchecker.semantic.adapters.kotlin2421

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.semantic.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

private val REQUEST_PATH = CompilerConfigurationKey.create<String>("ktChecker semantic request")
private val REQUEST_DIGEST = CompilerConfigurationKey.create<String>("ktChecker semantic request digest")
private val OUTPUT_PATH = CompilerConfigurationKey.create<String>("ktChecker semantic output directory")

/** Not enabled through ordinary project/compiler flags. An exact owner-selected invocation opts in. */
class SemanticCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = SEMANTIC_PLUGIN_ID
    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption("request", "path", "Exact owner-selected frozen semantic request", required = true),
        CliOption("request-digest", "sha256", "Digest binding the frozen request", required = true),
        CliOption("output", "directory", "Separate fresh evidence directory", required = true),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        val key = when (option.optionName) {
            "request" -> REQUEST_PATH
            "request-digest" -> REQUEST_DIGEST
            "output" -> OUTPUT_PATH
            else -> throw SemanticFactException("semantic_plugin_option_unknown", "Unknown semantic plugin option")
        }
        check(configuration.get(key) == null) { "Duplicate semantic plugin option" }
        configuration.put(key, value)
    }
}

class SemanticCompilerRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = SEMANTIC_PLUGIN_ID
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val requestPath = Path.of(configuration.get(REQUEST_PATH)
            ?: throw SemanticFactException("semantic_request_missing", "Semantic request is required"))
        if (!requestPath.isAbsolute || !Files.isRegularFile(requestPath, NOFOLLOW_LINKS) || Files.size(requestPath) > 1_048_576) {
            throw SemanticFactException("semantic_request_unsafe", "Semantic request must be a bounded regular file")
        }
        val request = ContractJson.decodeFromString<SemanticFactsRequest>(Files.readString(requestPath))
        if (request.digest() != configuration.get(REQUEST_DIGEST)) {
            throw SemanticFactException("semantic_request_digest_mismatch", "Semantic request changed after authorization")
        }
        val problems = SemanticFactsValidation.requestProblems(request)
        if (problems.isNotEmpty()) throw SemanticFactException(problems.first(), "Invalid selected semantic request: $problems")
        if (KotlinCompilerVersion.VERSION != SEMANTIC_COMPILER_VERSION) {
            throw SemanticFactException("semantic_compiler_version_mismatch", "This adapter requires exact Kotlin 2.4.21")
        }
        val ownJar = Path.of(SemanticCompilerRegistrar::class.java.protectionDomain.codeSource.location.toURI())
        verifyArtifact(ownJar, request.pluginArtifact.digest)
        val compilerJar = Path.of(CompilerPluginRegistrar::class.java.protectionDomain.codeSource.location.toURI())
        // An isolated invocation may copy artifacts into its private output tree. Identity is the
        // selected content digest, not a mutable cache path or a guessed versioned filename.
        val observedCompilerDigest = artifactDigest(compilerJar)
        val selectedCompiler = request.compilerArtifacts.singleOrNull { it.digest == observedCompilerDigest }
            ?: throw SemanticFactException("semantic_compiler_artifact_unselected", "Executing compiler implementation was not selected")
        check(selectedCompiler.digest == observedCompilerDigest)
        val output = Path.of(configuration.get(OUTPUT_PATH)
            ?: throw SemanticFactException("semantic_output_missing", "A separate output directory is required"))
        val emitter = SemanticFactEmitter(request, output)
        FirExtensionRegistrarAdapter.registerExtension(SemanticFirRegistrar(emitter))
    }
}

private class SemanticFirRegistrar(private val emitter: SemanticFactEmitter) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession -> SemanticCheckers(session, emitter) }
    }
}

private class SemanticCheckers(session: FirSession, emitter: SemanticFactEmitter) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val fileCheckers: Set<FirFileChecker> = setOf(object : FirFileChecker(MppCheckerKind.Common) {
            context(context: CheckerContext, reporter: DiagnosticReporter)
            override fun check(declaration: FirFile) { emitter.emit(declaration) }
        })
    }
}

/** One collector per compiler invocation, never global FIR state or cross-project mutable sessions. */
internal class SemanticFactEmitter(val request: SemanticFactsRequest, output: Path) {
    private val root = Path.of(request.sourceRoot).toRealPath()
    private val output: Path
    private val sources = request.sources.associateBy { root.resolve(it.path).normalize() }
    private val coordinates = mutableMapOf<String, SourceCoordinates>()
    private var writtenBytes = 0L
    private var writtenShards = 0

    init {
        if (!output.isAbsolute || !Files.isDirectory(output.parent, NOFOLLOW_LINKS) ||
            output.parent.toRealPath().startsWith(root) || root.startsWith(output.toAbsolutePath().normalize())) {
            throw SemanticFactException("semantic_output_overlap", "Semantic outputs must be outside immutable inputs")
        }
        this.output = output.parent.toRealPath().resolve(output.fileName)
        try {
            Files.createDirectory(this.output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } catch (_: UnsupportedOperationException) { Files.createDirectory(this.output) }
    }

    @Synchronized fun emit(file: FirFile) {
        if (++writtenShards > request.sources.size * 4) throw SemanticFactException("semantic_shard_limit", "Too many compiler source contexts")
        val input = source(file) ?: throw SemanticFactException("semantic_uncaptured_source", "Compiler visited a source absent from the frozen request")
        val shard = FirFactExtractor(this, file, input).extract()
        val bytes = ContractJson.encodeToString(shard).toByteArray(Charsets.UTF_8)
        writtenBytes += bytes.size
        if (writtenBytes > 16_777_216) throw SemanticFactException("semantic_output_limit", "Semantic facts exceed the bounded evidence size")
        val name = Digests.sha256(input.path + "\u0000" + shard.compilerContext.moduleName).removePrefix("sha256:") + ".json"
        val path = output.resolve(name)
        Files.write(path, bytes, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?.setPermissions(PosixFilePermissions.fromString("rw-------"))
    }

    fun source(file: FirFile): SemanticSource? = file.sourceFile?.path?.let { sources[Path.of(it).toAbsolutePath().normalize()] }

    fun coordinates(input: SemanticSource): SourceCoordinates = coordinates.getOrPut(input.path) {
        val path = root.resolve(input.path)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || path.toRealPath() != path || Files.size(path) > 4_194_304) {
            throw SemanticFactException("semantic_source_unsafe", "Compiler source is no longer an isolated bounded regular input")
        }
        SourceCoordinates(input, Files.readAllBytes(path))
    }
}

private fun verifyArtifact(path: Path, digest: String) {
    if (artifactDigest(path) != digest) throw SemanticFactException("semantic_artifact_digest_mismatch", "Executing artifact changed after selection")
}

private fun artifactDigest(path: Path): String {
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw SemanticFactException("semantic_artifact_unsafe", "Selected artifact is not a regular file")
    val hash = java.security.MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
        val bytes = ByteArray(65_536)
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            hash.update(bytes, 0, count)
        }
    }
    return "sha256:" + hash.digest().joinToString("") { "%02x".format(it) }
}

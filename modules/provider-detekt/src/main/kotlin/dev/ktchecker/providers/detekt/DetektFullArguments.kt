package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.Digests
import dev.ktchecker.projectmodel.ModelCompilation
import dev.ktchecker.projectmodel.ModelFile
import dev.ktchecker.providers.runtime.ExecutionProblem
import dev.ktchecker.providers.runtime.safeRelativePath
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Every configured original token is retained; this does NOT attest an executed compiler argv. */
internal enum class DetektFullArgumentRole {
    LANGUAGE_FLAG, LANGUAGE_VALUE, API_FLAG, API_VALUE, TARGET_FLAG, TARGET_VALUE,
    CLASSPATH_FLAG, CLASSPATH_VALUE, JDK_FLAG, JDK_VALUE, OUTPUT_FLAG, OUTPUT_VALUE,
    FREE_FLAG, FREE_VALUE, SOURCE,
}

internal data class DetektFullArgumentToken(val index: Int, val original: String,
    val role: DetektFullArgumentRole, val sourceOrdinal: Int? = null)

/**
 * Pure data projection, NOT a model origin or execution capability. It is intentionally separate
 * from the importer/compiler's Kotlin 2.4.21 role adapter. No original argv is rewritten in an
 * AnalysisContext; only this closed projection may feed the pinned Detekt 2.4.10 compiler API.
 */
internal class DetektFullArgumentProjection(
    originalArguments: List<String>,
    tokens: List<DetektFullArgumentToken>,
    sourceFiles: List<ModelFile>,
    freeCompilerArguments: List<String>,
    val languageVersion: String,
    val apiVersion: String,
    val jvmTarget: String,
    val originalDestination: String,
    val selectedJdkHome: String,
    val digest: String,
) {
    val originalArguments = immutableDetektList(originalArguments)
    val tokens = immutableDetektList(tokens)
    val sourceFiles = immutableDetektList(sourceFiles)
    val freeCompilerArguments = immutableDetektList(freeCompilerArguments)
    fun renderOriginal(): List<String> = tokens.map { it.original }
}

/**
 * All paths below come from the owner's retained capture/output/JDK selection and the SAME
 * strict imported compilation. In particular, never choose expectedDestination by reading -d.
 * This bounded adapter accepts only explicit language/API 2.4, JVM21 and nonempty Kotlin files.
 * Unknown/duplicate flags, scripts, plugins, response files and unaccounted operands abstain.
 */
internal fun projectDetektFullArguments(
    compilation: ModelCompilation,
    sourceRoot: Path,
    expectedDestination: Path,
    selectedJdk: Path,
): DetektFullArgumentProjection {
    fullPath(sourceRoot.toString())
    fullPath(expectedDestination.toString())
    fullPath(selectedJdk.toString())
    val roots = listOf(sourceRoot, expectedDestination, selectedJdk)
    roots.forEachIndexed { index, path -> roots.take(index).forEach { other ->
        fullCheck(!path.startsWith(other) && !other.startsWith(path), "detekt_full_argument_role_overlap",
            "Owner source, compiler output and selected JDK must be disjoint.")
    } }
    fullCheck(compilation.languageVersion == "2.4" && compilation.apiVersion == "2.4",
        "detekt_full_language_api_unsupported", "This initial boundary requires explicit Kotlin language/API 2.4.")
    val members = compilation.sourceFiles
    fullCheck(members.size in 1..64 && members.map { it.path }.distinct().size == members.size,
        "detekt_full_source_members_unsupported", "Exact bounded nonempty source membership is required.")
    val byPhysical = members.associateBy { file ->
        safeRelativePath(file.path)
        fullCheck(file.path.endsWith(".kt") && file.path.none { it == ':' || it == ',' || it.isISOControl() } &&
            Digests.valid(file.digest) && file.size in 0..33_554_432,
            "detekt_full_source_members_unsupported", "Only exact captured Kotlin files are supported.")
        sourceRoot.resolve(file.path).toString()
    }
    val classpath = compilation.classpath
    fullCheck(classpath.size in 1..16 && classpath.map { it.path }.distinct().size == classpath.size,
        "detekt_full_classpath_unsupported", "The original ordered classpath must contain one to sixteen distinct regular JARs.")
    classpath.forEach { artifact ->
        val path = fullPath(artifact.path)
        fullCheck(artifact.path.endsWith(".jar") && artifact.digest?.let(Digests::valid) == true &&
            artifact.size?.let { it in 1..536_870_912 } == true &&
            roots.none { path.startsWith(it) }, "detekt_full_classpath_unsupported",
            "Every original dependency needs exact external JAR identity; no directory/output inference.")
    }
    val expectedClasspath = classpath.joinToString(":") { it.path }
    val original = compilation.compilerArguments
    fullCheck(original.size in 1..128 && original.all { it.length in 1..8192 && it.none(Char::isISOControl) },
        "detekt_full_arguments_unsupported", "The complete original argv must be nonempty and bounded.")
    val tokens = mutableListOf<DetektFullArgumentToken>()
    val sources = mutableListOf<ModelFile>()
    val free = mutableListOf<String>()
    val seenFlags = mutableSetOf<String>()
    val seenSources = mutableSetOf<String>()
    var index = 0
    var sourcesStarted = false
    fun add(role: DetektFullArgumentRole, sourceOrdinal: Int? = null) {
        tokens += DetektFullArgumentToken(index, original[index], role, sourceOrdinal)
        index++
    }
    fun pair(flag: DetektFullArgumentRole, value: DetektFullArgumentRole, expected: String) {
        fullCheck(original.getOrNull(index + 1) == expected, "detekt_full_argument_operand_mismatch",
            "A scalar/path operand differs from the independently retained role or modeled value.")
        add(flag); add(value)
    }
    while (index < original.size) {
        val argument = original[index]
        if (!argument.startsWith("-")) {
            sourcesStarted = true
            val source = byPhysical[argument]
            fullCheck(source != null && seenSources.add(argument), "detekt_full_source_operand_mismatch",
                "Every free operand must be one unique exact captured source; no @response or foreign file.")
            add(DetektFullArgumentRole.SOURCE, sources.size)
            sources += requireNotNull(source)
            continue
        }
        fullCheck(!sourcesStarted && seenFlags.add(argument), "detekt_full_arguments_unsupported",
            "Only unique options before the contiguous original source operands are supported.")
        when (argument) {
            "-language-version" -> pair(DetektFullArgumentRole.LANGUAGE_FLAG, DetektFullArgumentRole.LANGUAGE_VALUE, "2.4")
            "-api-version" -> pair(DetektFullArgumentRole.API_FLAG, DetektFullArgumentRole.API_VALUE, "2.4")
            "-jvm-target" -> pair(DetektFullArgumentRole.TARGET_FLAG, DetektFullArgumentRole.TARGET_VALUE, "21")
            "-classpath" -> pair(DetektFullArgumentRole.CLASSPATH_FLAG, DetektFullArgumentRole.CLASSPATH_VALUE, expectedClasspath)
            "-jdk-home" -> pair(DetektFullArgumentRole.JDK_FLAG, DetektFullArgumentRole.JDK_VALUE, selectedJdk.toString())
            "-d" -> pair(DetektFullArgumentRole.OUTPUT_FLAG, DetektFullArgumentRole.OUTPUT_VALUE, expectedDestination.toString())
            "-no-stdlib", "-no-reflect", "-Xrender-internal-diagnostic-names", "-Xallow-no-source-files" -> {
                // These exact tokens go to the genuine embedded compiler argument parser;
                // even the nonempty-source gate is forwarded, not granted an inferred meaning.
                free += argument
                add(DetektFullArgumentRole.FREE_FLAG)
            }
            "-module-name" -> {
                val value = original.getOrNull(index + 1)
                fullCheck(value != null && Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}").matches(value),
                    "detekt_full_arguments_unsupported", "A module name must be one bounded scalar, never another option.")
                free += argument; free += requireNotNull(value)
                add(DetektFullArgumentRole.FREE_FLAG); add(DetektFullArgumentRole.FREE_VALUE)
            }
            else -> fullFailure("detekt_full_arguments_unsupported",
                "No unreviewed compiler, plugin, script, suppression or diagnostic-policy option may be silently omitted.")
        }
    }
    // Omitted compiler/Detekt module-name defaults have no reviewed equivalence; never infer one.
    fullCheck(seenFlags.containsAll(setOf("-language-version", "-api-version", "-jvm-target", "-classpath", "-d",
        "-module-name", "-no-stdlib", "-no-reflect")) && sources.size == members.size && seenSources == byPhysical.keys &&
        tokens.map { it.index } == original.indices.toList() && tokens.map { it.original } == original,
        "detekt_full_arguments_incomplete", "Every required scalar/path/profile role and source occurrence must be accounted exactly once.")
    val relation = buildJsonObject {
        put("schema", "ktchecker/detekt-full-original-arguments-2.4.10/1")
        put("projectCompiler", DETEKT_EMBEDDED_KOTLIN); put("embeddedCompiler", DETEKT_EMBEDDED_KOTLIN)
        put("sourceRoot", sourceRoot.toString()); put("selectedJdkHome", selectedJdk.toString())
        put("originalCompilerDestination", expectedDestination.toString())
        put("standaloneCompilerArgvObserved", false)
        put("compilerEmissionExecuted", false)
        put("languageVersion", "2.4"); put("apiVersion", "2.4"); put("jvmTarget", "21")
        putJsonArray("modelSourceOrder") { members.forEach { add(it.path) } }
        putJsonArray("originalArgvSourceOrder") { sources.forEach { add(it.path) } }
        putJsonArray("classpathOrder") { classpath.forEach { artifact -> add(buildJsonObject {
            put("coordinate", artifact.coordinate); put("path", artifact.path)
            put("digest", artifact.digest); put("size", artifact.size)
        }) } }
        putJsonArray("freeCompilerArguments") { free.forEach { add(it) } }
        putJsonArray("tokens") { tokens.forEach { token -> add(buildJsonObject {
            put("index", token.index); put("original", token.original); put("role", token.role.name)
            token.sourceOrdinal?.let { put("sourceOrdinal", it) }
        }) } }
    }
    return DetektFullArgumentProjection(original, tokens, sources, free, "2.4", "2.4", "21",
        expectedDestination.toString(), selectedJdk.toString(), fullJsonDigest(relation))
}

internal fun fullPath(value: String): Path {
    fullCheck(value.length in 2..4096 && value.startsWith("/") &&
        value.none { it.isISOControl() || it == '\\' || it == ':' || it == ',' },
        "detekt_full_path_unsupported", "This explicit initial seam uses unaliased normalized POSIX paths.")
    val path = Path.of(value)
    fullCheck(path.isAbsolute && path.normalize() == path && path.toString() == value,
        "detekt_full_path_unsupported", "Paths cannot be normalized or retargeted into authority.")
    return path
}

internal fun fullJsonDigest(value: JsonElement): String = Digests.sha256(Digests.canonical(value))
internal fun fullCheck(valid: Boolean, code: String, message: String) {
    if (!valid) fullFailure(code, message)
}
internal fun fullFailure(code: String, message: String): Nothing = throw ExecutionProblem(code, message)

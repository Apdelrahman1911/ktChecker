package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Path

/**
 * Optional ORIGINAL all-type KGP argument facts, never an execution/relocation grant.
 * The pinned exporter obtains every field from the SAME successfully created arguments object
 * and independently cross-checks the actual task. Null and empty operands remain different.
 * Source order is the original free-argument order, NOT sorted ModelCompilation membership.
 */
@Serializable
data class ModelJvmArgumentRoles(
    val schemaVersion: Int = 1,
    val adapterId: String = JVM_ARGUMENT_ROLES_ADAPTER,
    val compileTaskPath: String,
    val sourcePaths: List<String>,
    val classpathOperand: String?,
    val destinationPath: String,
    val selectedJdkHome: String,
    val jdkHomeOperand: String?,
    /** Only emitted after independent plugin/options/input/output/script observations are empty. */
    val pluginDataState: String,
)

const val JVM_ARGUMENT_ROLES_ADAPTER = "ktchecker/kgp-2.4.21-original-jvm-roles/1"

/**
 * Trusted owner integration ONLY, like ModelIngressPermit. This is neither serializable nor an
 * execution permit. The owner must obtain task/spec/JDK/output from its registered capture and
 * actual successful exporter invocation, NEVER construct them by reading the report's claims.
 * A callback must return the SAME preselected object at both importer checks and reverify its
 * registered origin each time. A JSON copy, normalized path, or present output tree is no proof.
 *
 * Bounded layout: ordinary JVM target, owner-isolated Gradle build directory, unmodified default
 * Kotlin destination. Custom outputs/targets require another explicit adapter, not a prefix match.
 */
class ModelJvmArgumentOrigin(
    request: ModelImportRequest,
    val compilationId: String,
    val compileTaskPath: String,
    val moduleId: String,
    val compilationName: String,
    ownerExporterOutput: Path,
    selectedJdkHome: Path,
) {
    val requestDigest: String = freezeRequest(request).digest()
    val sourceRoot: Path = jvmRoleAbsolute(request.materializedRoot)
    val ownerExporterOutput: Path = jvmRoleAbsolute(ownerExporterOutput.toString())
    val selectedJdkHome: Path = jvmRoleAbsolute(selectedJdkHome.toString())
    val destinationPath: Path

    init {
        identifier(compilationId); identifier(compileTaskPath); identifier(moduleId)
        jvmRoleCheck(Regex("[A-Za-z][A-Za-z0-9_]{0,63}").matches(compilationName), "owner compilation name")
        jvmRoleCheck(moduleId == ":" || Regex(":[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*").matches(moduleId), "owner module identity")
        jvmRoleCheck(compilationId == "$moduleId||$compilationName" && compileTaskPath.startsWith(":"), "owner ordinary JVM task identity")
        val roots = listOf(sourceRoot, this.ownerExporterOutput, this.selectedJdkHome)
        roots.forEachIndexed { index, path -> roots.take(index).forEach { other ->
            jvmRoleCheck(!path.startsWith(other) && !other.startsWith(path), "disjoint owner roles")
        } }
        val projectDirectory = Digests.sha256(moduleId).removePrefix("sha256:")
        destinationPath = this.ownerExporterOutput.resolve("gradle-build/$projectDirectory/classes/kotlin/$compilationName")
    }
}

/** Each ORIGINAL token has exactly one index and role. Pairs retain both flag and operand. */
enum class JvmArgumentTokenRole { SCALAR_FLAG, SCALAR_VALUE, FIXED_PROFILE_FLAG, NONEMPTY_SOURCE_GATE,
    CLASSPATH_FLAG, CLASSPATH_VALUE, DESTINATION_FLAG, DESTINATION_VALUE, JDK_FLAG, JDK_VALUE, SOURCE }

data class JvmArgumentToken internal constructor(val index: Int, val original: String, val role: JvmArgumentTokenRole,
    val sourceOrdinal: Int? = null)

/**
 * Importer-created checked relation, not compiler evidence or a process capability. A pure JSON
 * role declaration cannot construct this class. The original vector remains in model/context at
 * P/K/C/E. Only these distinct typed projections may feed the fixed worker's bounded vocabulary.
 */
class VerifiedJvmArgumentLayout internal constructor(
    internal val origin: ModelJvmArgumentOrigin,
    originalArguments: List<String>,
    tokens: List<JvmArgumentToken>,
    normalizedCompilerOptions: List<String>,
    modelSourceFiles: List<ModelFile>,
    executableSourceFiles: List<ModelFile>,
    val digest: String,
) {
    val originalArguments: List<String> = immutable(originalArguments)
    val tokens: List<JvmArgumentToken> = immutable(tokens)
    val normalizedCompilerOptions: List<String> = immutable(normalizedCompilerOptions)
    /** Exact original model membership order, distinct from the original source-token order. */
    val modelSourceFiles: List<ModelFile> = immutable(modelSourceFiles)
    val executableSourceFiles: List<ModelFile> = immutable(executableSourceFiles)
    val executableCompilationFiles: List<String> = immutable(executableSourceFiles.map { it.path })
    val originalSourceRoot: String = origin.sourceRoot.toString()
    val originalDestination: String = origin.destinationPath.toString()
    val selectedJdkHome: String = origin.selectedJdkHome.toString()
    fun renderOriginal(): List<String> = immutable(tokens.map { it.original })

    companion object {
        const val SCHEMA = "ktchecker/verified-original-jvm-argument-layout/1"
        // Normalization claims are confined to this pinned compiler, not merely a version string.
        const val COMPILER_DIGEST = "sha256:ef19419c765e7ac8404465fa026ca8fc4dbb8a822de0fc79aa53c8dce29f1d02"
    }
}

/** Pure bounded validation: no file reads, normalization/stripping fallback or capability lookup. */
internal fun classifyJvmArgumentLayout(request: ModelImportRequest, compilation: ModelCompilation,
    origin: ModelJvmArgumentOrigin, generatedInputs: List<GeneratedSourceInput>): VerifiedJvmArgumentLayout {
    val roles = compilation.jvmArgumentRoles ?: jvmRoleFailure("Missing original role declaration")
    val chain = compilation.toolchain ?: jvmRoleFailure("Missing original JVM toolchain")
    jvmRoleCheck(roles.schemaVersion == 1 && roles.adapterId == JVM_ARGUMENT_ROLES_ADAPTER, "role adapter version")
    jvmRoleCheck(origin.requestDigest == request.digest() && origin.sourceRoot.toString() == request.materializedRoot &&
        origin.compilationId == compilation.id && origin.compileTaskPath == compilation.compileTaskPath &&
        origin.moduleId == compilation.moduleId && origin.compilationName == compilation.name &&
        roles.compileTaskPath == compilation.compileTaskPath, "exact registered owner task/request origin")
    jvmRoleCheck(compilation.platform == "jvm" && compilation.target.isEmpty() && chain.kind == "jvm" &&
        chain.compilerVersion == "2.4.21", "ordinary pinned JVM domain")
    jvmRoleCheck(chain.compilerArtifacts.singleOrNull {
        it.coordinate == "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21"
    }?.digest == VerifiedJvmArgumentLayout.COMPILER_DIGEST, "unique exact compiler pin for normalization")
    jvmRoleCheck(compilation.friendArtifacts.isEmpty() && compilation.associatedCompilationIds.isEmpty() &&
        compilation.auxiliaryArtifacts.isEmpty() && compilation.auxiliaryInputFiles.isEmpty() &&
        chain.compilerPlugins.isEmpty() && compilation.projectDependencies.isEmpty() &&
        generatedInputs.none { generated -> generated.sourceSetIds.any { it in compilation.sourceSetIds } },
        "plugin/friend/Java/script/auxiliary/generated/project-produced domain")
    jvmRoleCheck(roles.pluginDataState in setOf("provider_absent", "value_absent", "available_empty"), "observed empty plugin state")
    jvmRoleCheck(roles.destinationPath == origin.destinationPath.toString() &&
        roles.selectedJdkHome == origin.selectedJdkHome.toString() &&
        (roles.jdkHomeOperand == null || roles.jdkHomeOperand == origin.selectedJdkHome.toString()), "owner JDK/destination role equality")
    jvmRoleAbsolute(roles.destinationPath); jvmRoleAbsolute(roles.selectedJdkHome)
    roles.jdkHomeOperand?.let(::jvmRoleAbsolute)

    val memberFiles = compilation.sourceFiles
    jvmRoleCheck(memberFiles.size in 1..64 && memberFiles.map { it.path }.distinct().size == memberFiles.size,
        "bounded nonempty complete source membership")
    memberFiles.forEach { file ->
        logicalPath(file.path)
        jvmRoleCheck(file.path.endsWith(".kt") && ':' !in file.path && ',' !in file.path &&
            Digests.valid(file.digest) && file.size >= 0, "exact captured Kotlin source")
    }
    jvmRoleCheck(roles.sourcePaths.size == memberFiles.size && roles.sourcePaths.distinct().size == roles.sourcePaths.size &&
        roles.sourcePaths.toSet() == memberFiles.map { it.path }.toSet(), "bijective source membership")
    val members = memberFiles.associateBy { it.path }
    val sourceFiles = roles.sourcePaths.map { members.getValue(it) }
    val sourceArguments = sourceFiles.map { jvmRoleAbsolute(origin.sourceRoot.resolve(it.path).toString()).toString() }
    // The live adapter does not reinterpret absent/empty classpath as an owned empty directory.
    // These original cases remain report data and are explicitly unsupported here.
    val classpath = compilation.classpath
    jvmRoleCheck(classpath.size in 1..16 && classpath.map { it.path }.distinct().size == classpath.size,
        "nonempty ordered regular JAR classpath domain")
    classpath.forEach { artifact ->
        jvmRoleAbsolute(artifact.path)
        jvmRoleCheck(artifact.path.endsWith(".jar") && artifact.digest?.let(Digests::valid) == true &&
            artifact.size != null && artifact.size > 0, "exact regular JAR classpath identity")
        val path = Path.of(artifact.path)
        jvmRoleCheck(!path.startsWith(origin.sourceRoot) && !path.startsWith(origin.ownerExporterOutput) &&
            !path.startsWith(origin.selectedJdkHome), "classpath role overlap")
    }
    val expectedClasspath = classpath.joinToString(":") { it.path }
    jvmRoleCheck(roles.classpathOperand != null && roles.classpathOperand == expectedClasspath, "entire ordered classpath operand")

    val original = compilation.compilerArguments
    jvmRoleCheck(original.size in 1..128 && original.all { it.length in 1..8192 && it.none(Char::isISOControl) }, "original argument bounds")
    val tokens = mutableListOf<JvmArgumentToken>()
    val options = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    val scalarValues = linkedMapOf<String, String>()
    var index = 0
    var sourceOrdinal = 0
    var sourcesStarted = false
    fun add(role: JvmArgumentTokenRole, ordinal: Int? = null) {
        tokens += JvmArgumentToken(index, original[index], role, ordinal); index++
    }
    fun pair(flagRole: JvmArgumentTokenRole, valueRole: JvmArgumentTokenRole, expected: String) {
        jvmRoleCheck(index + 1 < original.size && original[index + 1] == expected, "exact path operand")
        add(flagRole); add(valueRole)
    }
    while (index < original.size) {
        val argument = original[index]
        if (!argument.startsWith("-")) {
            sourcesStarted = true
            jvmRoleCheck(sourceOrdinal < sourceArguments.size && argument == sourceArguments[sourceOrdinal], "ordered original source occurrence")
            add(JvmArgumentTokenRole.SOURCE, sourceOrdinal++)
            continue
        }
        jvmRoleCheck(!sourcesStarted && seen.add(argument), "unique options before contiguous source operands")
        when (argument) {
            "-no-stdlib", "-no-reflect", "-Xrender-internal-diagnostic-names" -> add(JvmArgumentTokenRole.FIXED_PROFILE_FLAG)
            "-Xallow-no-source-files" -> {
                // Pinned JVM source-free gates only. See PINNED-ARGUMENT-NORMALIZATION.md.
                // Every admitted call separately retains existing nonempty regular .kt sources.
                jvmRoleCheck(sourceFiles.isNotEmpty(), "inactive source-free gate")
                add(JvmArgumentTokenRole.NONEMPTY_SOURCE_GATE)
            }
            "-classpath" -> pair(JvmArgumentTokenRole.CLASSPATH_FLAG, JvmArgumentTokenRole.CLASSPATH_VALUE, expectedClasspath)
            "-d" -> pair(JvmArgumentTokenRole.DESTINATION_FLAG, JvmArgumentTokenRole.DESTINATION_VALUE, roles.destinationPath)
            "-jdk-home" -> pair(JvmArgumentTokenRole.JDK_FLAG, JvmArgumentTokenRole.JDK_VALUE,
                roles.jdkHomeOperand ?: jvmRoleFailure("JDK operand absent in original arguments object"))
            "-progressive", "-Werror", "-nowarn" -> { options += argument; add(JvmArgumentTokenRole.SCALAR_FLAG) }
            "-language-version", "-api-version", "-jvm-target", "-module-name" -> {
                val value = original.getOrNull(index + 1) ?: jvmRoleFailure("Missing scalar value")
                val supported = when (argument) {
                    "-language-version", "-api-version" -> value in setOf("2.0", "2.1", "2.2", "2.3", "2.4")
                    "-jvm-target" -> value in setOf("1.8", "11", "17", "21")
                    else -> Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}").matches(value)
                }
                jvmRoleCheck(supported, "fixed scalar profile")
                scalarValues[argument] = value
                options += argument; options += value
                add(JvmArgumentTokenRole.SCALAR_FLAG); add(JvmArgumentTokenRole.SCALAR_VALUE)
            }
            else -> jvmRoleFailure("Unknown/response/plugin/friend/JVM/Java/refinement argument")
        }
    }
    jvmRoleCheck(sourceOrdinal == sourceFiles.size && tokens.size == original.size &&
        tokens.map { it.index } == original.indices.toList() && tokens.map { it.original } == original, "total original-vector reconstruction")
    jvmRoleCheck(seen.containsAll(setOf("-classpath", "-d", "-no-stdlib", "-no-reflect", "-jvm-target")) &&
        (("-jdk-home" in seen) == (roles.jdkHomeOperand != null)), "required exact fixed/path operands")
    jvmRoleCheck(options.size <= 16 && (compilation.languageVersion == null || scalarValues["-language-version"] == compilation.languageVersion) &&
        (compilation.apiVersion == null || scalarValues["-api-version"] == compilation.apiVersion), "scalar/domain agreement")
    val relation = buildJsonObject {
        put("schema", VerifiedJvmArgumentLayout.SCHEMA)
        put("requiredCompilerDigest", VerifiedJvmArgumentLayout.COMPILER_DIGEST)
        put("ownerRequest", origin.requestDigest)
        put("ownerSourceRoot", origin.sourceRoot.toString()); put("ownerExporterOutput", origin.ownerExporterOutput.toString())
        put("ownerSelectedJdk", origin.selectedJdkHome.toString()); put("ownerDestination", origin.destinationPath.toString())
        put("compilationId", compilation.id); put("compileTaskPath", compilation.compileTaskPath)
        put("declaredRoles", ContractJson.encodeToJsonElement(roles))
        put("originalArguments", ContractJson.encodeToJsonElement(original))
        put("modelMemberOrder", ContractJson.encodeToJsonElement(memberFiles))
        put("executableSourceOrder", ContractJson.encodeToJsonElement(sourceFiles))
        put("orderedClasspath", ContractJson.encodeToJsonElement(classpath))
        put("normalizedCompilerOptions", ContractJson.encodeToJsonElement(options))
        putJsonArray("tokens") { tokens.forEach { token -> add(buildJsonObject {
            put("index", token.index); put("original", token.original); put("role", token.role.name)
            token.sourceOrdinal?.let { put("sourceOrdinal", it) }
        }) } }
        put("normalization", "fixed-no-stdlib-no-reflect;selected-jdk;owned-destination;exact-classpath-and-source-relocation;nonempty-source-gate;diagnostic-names")
    }
    return VerifiedJvmArgumentLayout(origin, original, tokens, options, memberFiles, sourceFiles, canonicalDigest(relation))
}

private fun jvmRoleAbsolute(value: String): Path {
    jvmRoleCheck(value.length in 2..4096 && value.startsWith('/') && value.none(Char::isISOControl) &&
        '\\' !in value && ':' !in value && ',' !in value, "unaliased POSIX physical role path")
    val path = Path.of(value)
    jvmRoleCheck(path.isAbsolute && path.normalize() == path && path.toString() == value, "normalized absolute role path")
    return path
}

private fun jvmRoleCheck(valid: Boolean, description: String) { if (!valid) jvmRoleFailure(description) }
private fun jvmRoleFailure(description: String): Nothing = throw ProjectModelException("model_jvm_arguments_unsupported", "Unsupported $description.")

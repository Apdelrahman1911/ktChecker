package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.Digests
import dev.ktchecker.projectmodel.ModelArtifact
import dev.ktchecker.projectmodel.ModelCompilation
import dev.ktchecker.projectmodel.ModelFile
import dev.ktchecker.projectmodel.ModelToolchain
import dev.ktchecker.providers.runtime.ExecutionProblem
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Pure total-token checks on the host test JVM; no file reads, importer or child process calls. */
class DetektFullArgumentsTest {
    private val root = Path.of("/owner/source")
    private val output = Path.of("/owner/compiler-output/main")
    private val jdk = Path.of("/owner/selected-jdk")
    private val a = ModelFile("src/main/A.kt", Digests.sha256("first source"), 12)
    private val b = ModelFile("src/main/B.kt", Digests.sha256("second source"), 13)
    private val dependencies = listOf(
        ModelArtifact("fixture:first:1", "/owner/dependencies/first.jar", Digests.sha256("first"), 5),
        ModelArtifact("fixture:second:1", "/owner/dependencies/second.jar", Digests.sha256("second"), 6),
    )

    private fun compilation(jdkOperand: Boolean = true): ModelCompilation {
        val options = listOf("-no-stdlib", "-no-reflect", "-language-version", "2.4", "-api-version", "2.4",
            "-jvm-target", "21", "-module-name", "fixture_main", "-classpath", dependencies.joinToString(":") { it.path },
            "-d", output.toString(), "-Xrender-internal-diagnostic-names", "-Xallow-no-source-files") +
            if (jdkOperand) listOf("-jdk-home", jdk.toString()) else emptyList()
        return ModelCompilation(":fixture||main", ":fixture", "", "jvm", "main", ":fixture|main", listOf(":fixture|main"),
            listOf(b, a), ":fixture:compileKotlin", "compileClasspath", dependencies, emptyList(),
            ModelToolchain("jvm", "2.4.10", emptyList(), emptyList()),
            options + listOf(root.resolve(a.path).toString(), root.resolve(b.path).toString()), "2.4", "2.4")
    }

    private fun project(value: ModelCompilation = compilation()) = projectDetektFullArguments(value, root, output, jdk)
    private fun failure(code: String, action: () -> Unit) =
        assertEquals(code, assertFailsWith<ExecutionProblem>(block = action).code)
    private fun rejected(value: ModelCompilation) = assertFailsWith<ExecutionProblem> { project(value) }
    private fun replaceOperand(value: ModelCompilation, flag: String, operand: String): ModelCompilation =
        value.copy(compilerArguments = value.compilerArguments.toMutableList().also { it[it.indexOf(flag) + 1] = operand })

    @Test fun `every original token is retained and source execution order is not model order`() {
        val original = compilation()
        val projected = project(original)
        assertEquals(original.compilerArguments, projected.originalArguments)
        assertEquals(original.compilerArguments, projected.renderOriginal())
        assertEquals(original.compilerArguments.indices.toList(), projected.tokens.map { it.index })
        assertEquals(listOf(b.path, a.path), original.sourceFiles.map { it.path })
        assertEquals(listOf(a.path, b.path), projected.sourceFiles.map { it.path })
        assertEquals(listOf(0, 1), projected.tokens.filter { it.role == DetektFullArgumentRole.SOURCE }.map { it.sourceOrdinal })
        assertEquals(output.toString(), projected.originalDestination)
        assertEquals(jdk.toString(), projected.selectedJdkHome)
        assertEquals(listOf("-no-stdlib", "-no-reflect", "-module-name", "fixture_main",
            "-Xrender-internal-diagnostic-names", "-Xallow-no-source-files"), projected.freeCompilerArguments)
        assertFalse("-d" in projected.freeCompilerArguments)
        assertFalse(output.toString() in projected.freeCompilerArguments)
        assertEquals(listOf(DetektFullArgumentRole.OUTPUT_FLAG, DetektFullArgumentRole.OUTPUT_VALUE),
            projected.tokens.filter { it.original == "-d" || it.original == output.toString() }.map { it.role })
        assertTrue(Digests.valid(projected.digest))
    }

    @Test fun `optional original JDK operand never changes the independently selected JDK`() {
        val explicit = project(compilation(true))
        val implicit = project(compilation(false))
        assertEquals(explicit.selectedJdkHome, implicit.selectedJdkHome)
        assertEquals(explicit.freeCompilerArguments, implicit.freeCompilerArguments)
        assertNotEquals(explicit.digest, implicit.digest)
        assertFalse(implicit.tokens.any { it.role == DetektFullArgumentRole.JDK_FLAG })
        assertEquals(compilation(false).compilerArguments, implicit.renderOriginal())
    }

    @Test fun `omitted required flags operands and source occurrences fail closed`() {
        val base = compilation()
        for (flag in listOf("-language-version", "-api-version", "-jvm-target", "-classpath", "-d", "-module-name", "-no-stdlib", "-no-reflect")) {
            val removed = base.compilerArguments.toMutableList()
            val at = removed.indexOf(flag)
            if (flag !in setOf("-no-stdlib", "-no-reflect")) removed.removeAt(at + 1)
            removed.removeAt(at)
            failure("detekt_full_arguments_incomplete") { project(base.copy(compilerArguments = removed)) }
        }
        failure("detekt_full_arguments_incomplete") { project(base.copy(compilerArguments = base.compilerArguments.dropLast(1))) }
        rejected(base.copy(compilerArguments = listOf("-classpath")))
        rejected(base.copy(compilerArguments = emptyList()))
    }

    @Test fun `duplicate flags and flags after sources are never accepted`() {
        val base = compilation()
        for (extra in listOf(listOf("-no-stdlib"), listOf("-language-version", "2.4"),
            listOf("-module-name", "fixture_main"), listOf("-jdk-home", jdk.toString()))) {
            failure("detekt_full_arguments_unsupported") {
                project(base.copy(compilerArguments = extra + base.compilerArguments))
            }
        }
        failure("detekt_full_arguments_unsupported") {
            project(base.copy(compilerArguments = base.compilerArguments + "-Xunknown"))
        }
        val withoutModule = base.compilerArguments.toMutableList().also { args ->
            val at = args.indexOf("-module-name"); args.removeAt(at + 1); args.removeAt(at)
        }
        failure("detekt_full_arguments_unsupported") {
            project(base.copy(compilerArguments = withoutModule + listOf("-module-name", "after_source")))
        }
    }

    @Test fun `unknown diagnostic policy plugin friends script response and joined flags are rejected`() {
        val base = compilation()
        for (token in listOf("-progressive", "-Werror", "-nowarn", "-Xplugin=/owner/plugin.jar", "-P",
            "-Xfriend-paths=/owner/friend.jar", "-script", "-expression", "--", "-classpath=/owner/dependencies/first.jar",
            "-Xcontext-parameters", "-Xskip-prerelease-check", "-Xjdk-release=21", "-java-parameters")) {
            failure("detekt_full_arguments_unsupported") {
                project(base.copy(compilerArguments = listOf(token) + base.compilerArguments))
            }
        }
        for (token in listOf("@/owner/options.args", "/owner/source/script.kts", "/owner/source/src/main/Foreign.kt", "unclaimed")) {
            failure("detekt_full_source_operand_mismatch") {
                project(base.copy(compilerArguments = base.compilerArguments + token))
            }
        }
    }

    @Test fun `explicit language API and JVM version metadata cannot disagree or use defaults`() {
        val base = compilation()
        for (metadata in listOf(base.copy(languageVersion = null), base.copy(apiVersion = null),
            base.copy(languageVersion = "2.3"), base.copy(apiVersion = "2.5"))) {
            failure("detekt_full_language_api_unsupported") { project(metadata) }
        }
        for ((flag, operand) in listOf("-language-version" to "2.3", "-api-version" to "2.5", "-jvm-target" to "17")) {
            failure("detekt_full_argument_operand_mismatch") { project(replaceOperand(base, flag, operand)) }
        }
    }

    @Test fun `original ordered classpath operand cannot be inferred sorted dropped or replaced`() {
        val base = compilation()
        for (operand in listOf(dependencies.reversed().joinToString(":") { it.path }, dependencies.first().path,
            dependencies.joinToString(":") { it.path } + ":/owner/unmodeled.jar", "/owner/dependencies/./first.jar")) {
            failure("detekt_full_argument_operand_mismatch") { project(replaceOperand(base, "-classpath", operand)) }
        }
        failure("detekt_full_argument_operand_mismatch") { project(base.copy(classpath = dependencies.reversed())) }
        failure("detekt_full_classpath_unsupported") { project(base.copy(classpath = listOf(dependencies.first(), dependencies.first()))) }
        failure("detekt_full_classpath_unsupported") { project(base.copy(classpath = emptyList())) }
    }

    @Test fun `artifact directory absent hash absent size and source output or JDK overlap are unsupported`() {
        val base = compilation()
        val first = dependencies.first()
        for (artifact in listOf(first.copy(path = "/owner/dependencies/classes"), first.copy(digest = null),
            first.copy(digest = "not-a-digest"), first.copy(size = null), first.copy(size = 0),
            first.copy(size = 536_870_913), first.copy(path = root.resolve("bundled.jar").toString()),
            first.copy(path = output.resolve("classes.jar").toString()), first.copy(path = jdk.resolve("lib/extra.jar").toString()))) {
            failure("detekt_full_classpath_unsupported") { project(base.copy(classpath = listOf(artifact))) }
        }
    }

    @Test fun `source operand bijection does not permit duplicates scripts or noncaptured members`() {
        val base = compilation()
        failure("detekt_full_source_operand_mismatch") {
            project(base.copy(compilerArguments = base.compilerArguments.dropLast(1) + root.resolve(a.path).toString()))
        }
        for (members in listOf(emptyList(), listOf(a, a), listOf(a.copy(path = "src/main/A.kts")), listOf(a.copy(path = "src/main/A.java")),
            listOf(a.copy(digest = "unknown")), listOf(a.copy(size = -1)), listOf(a.copy(size = 33_554_433)))) {
            failure("detekt_full_source_members_unsupported") { project(base.copy(sourceFiles = members)) }
        }
    }

    @Test fun `independent destination and JDK roles cannot be derived from the argument being checked`() {
        val base = compilation()
        failure("detekt_full_argument_operand_mismatch") {
            projectDetektFullArguments(base, root, Path.of("/owner/foreign-output"), jdk)
        }
        failure("detekt_full_argument_operand_mismatch") {
            projectDetektFullArguments(base, root, output, Path.of("/owner/foreign-jdk"))
        }
        for (operand in listOf(output.toString() + "/../main", output.toString() + "/", "relative/output")) {
            failure("detekt_full_argument_operand_mismatch") { project(replaceOperand(base, "-d", operand)) }
        }
        failure("detekt_full_argument_role_overlap") {
            projectDetektFullArguments(base, root, root.resolve("compiler-output"), jdk)
        }
        failure("detekt_full_argument_role_overlap") {
            projectDetektFullArguments(base, root, output, Path.of("/owner"))
        }
    }

    @Test fun `path aliases delimiter ambiguity and unbounded argument data are rejected`() {
        for (path in listOf("relative", "/owner/./source", "/owner/source/../source", "/owner//source", "/owner/source/",
            "/owner\\source", "/owner:source", "/owner,source", "/owner\nsource")) {
            failure("detekt_full_path_unsupported") { fullPath(path) }
        }
        val base = compilation()
        for (token in listOf("", "x".repeat(8193), "line\nbreak", "nul\u0000token")) {
            failure("detekt_full_arguments_unsupported") {
                project(base.copy(compilerArguments = base.compilerArguments + token))
            }
        }
        rejected(base.copy(compilerArguments = List(129) { "operand" }))
        for (name in listOf("-option", "bad/name", "", "x".repeat(129))) {
            failure("detekt_full_arguments_unsupported") { project(replaceOperand(base, "-module-name", name)) }
        }
    }

    @Test fun `projection freezes callers and identity includes source membership and original execution orders`() {
        val base = compilation()
        val arguments = base.compilerArguments.toMutableList()
        val sources = base.sourceFiles.toMutableList()
        val projected = project(base.copy(compilerArguments = arguments, sourceFiles = sources))
        arguments.clear(); sources.clear()
        assertEquals(base.compilerArguments, projected.renderOriginal())
        assertNotEquals(projected.digest, project(base.copy(sourceFiles = base.sourceFiles.reversed())).digest)
        val flippedExecution = base.compilerArguments.dropLast(2) + base.compilerArguments.takeLast(2).reversed()
        assertNotEquals(projected.digest, project(base.copy(compilerArguments = flippedExecution)).digest)
        assertFails { (projected.originalArguments as MutableList<String>).clear() }
        assertFails { (projected.sourceFiles as MutableList<ModelFile>).clear() }
        assertFails { (projected.tokens as MutableList<DetektFullArgumentToken>).clear() }
        assertFails { (projected.freeCompilerArguments as MutableList<String>).clear() }
    }
}

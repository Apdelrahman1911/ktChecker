package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.AnalysisContext
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.RuleContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.test.*

/** Registered unit-capture model facts only, not real Gradle/compiler execution evidence. */
class PlainJvmModelTargetTest {
    @Test fun fourExactEmptyTargetsRemainDistinctContextsAndCompleteReviewObligations(): Unit {
        val f = fixture()
        val model = f.owner.import(f.capture, f.graph)
        val ids = listOf(":consumer||main", ":consumer||test", ":lib||main", ":lib||test")
        assertEquals(ids, model.compilationIds)
        val contexts = ids.map { id -> model.context(id, f.owner.configurationDigest) }
        assertTrue(contexts.all { it.completeness == ModelCompleteness.COMPLETE })
        assertTrue(contexts.all { it.context!!.scope.target == "" })
        assertEquals(4, contexts.map { it.context!!.scope.id }.distinct().size)
        contexts.forEach { verified ->
            val context = verified.context!!
            assertEquals(context, ContractJson.decodeFromString<AnalysisContext>(ContractJson.encodeToString(context)))
        }
        assertEquals(":lib||main", model.compilation(":consumer||main")!!.projectDependencies.single().compilationId)
        assertEquals(listOf(":consumer||main"), model.compilation(":consumer||test")!!.associatedCompilationIds)
        assertEquals(ids, ProjectImpactPlanner().impact(null, model).obligations.map { it.compilationId })
        assertNull(model.context(RuleContext("jvm", "main", ":lib", "main"), f.owner.configurationDigest).context,
            "An exact empty target never becomes an inferred jvm alias")
    }

    @Test fun missingJvmPluginMppAndroidAndAgpCannotAdmitAnEmptyTarget(): Unit {
        val f = fixture()
        for (plugins in listOf(emptyList(), listOf("org.jetbrains.kotlin.multiplatform"),
            listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.multiplatform"),
            listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.android"),
            listOf("org.jetbrains.kotlin.jvm", "com.android.application"),
            listOf("org.jetbrains.kotlin.jvm", "com.android.test"))) {
            val changed = f.graph.copy(modules = f.graph.modules.map { if (it.id == ":lib") it.copy(pluginIds = plugins) else it })
            assertEquals("model_invalid", assertOwnerFixtureModelFailure(f.owner.work) { f.owner.import(f.capture, changed) }.code)
        }
    }

    @Test fun mixedTargetDomainsNonJvmAndWhitespaceAreNotNormalizedIntoTheJvmOnlyCase(): Unit {
        val f = fixture()
        val selected = f.graph.compilations.last()
        for (changed in listOf(selected.copy(target = "jvm"), selected.copy(platform = "js"), selected.copy(target = " "),
            selected.copy(target = "\t"), selected.copy(target = "\u0000"), selected.copy(target = "x".repeat(4097)))) {
            assertEquals("model_invalid", assertOwnerFixtureModelFailure(f.owner.work) {
                f.owner.import(f.capture, f.graph.copy(compilations = f.graph.compilations.map { if (it.id == selected.id) changed else it }))
            }.code)
        }
        // The eligibility gate is per module: another module's actual named target is
        // neither a wildcard nor authority for this module's exact empty target.
        val consumerNamed = renameTargets(f.graph, ":consumer", "namedJvm")
        val model = f.owner.import(f.capture, consumerNamed)
        assertEquals("", model.context(":lib||main", f.owner.configurationDigest).context!!.scope.target)
        assertEquals("namedJvm", model.context(":consumer|namedJvm|main", f.owner.configurationDigest).context!!.scope.target)
    }

    @Test fun emptyTargetDoesNotRelaxAnyOtherCompilationIdentity(): Unit {
        val f = fixture()
        val selected = f.graph.compilations.first()
        for (changed in listOf(selected.copy(id = ""), selected.copy(platform = ""), selected.copy(name = ""),
            selected.copy(compileTaskPath = ""), selected.copy(dependencyConfiguration = ""))) {
            assertEquals("model_invalid", assertOwnerFixtureModelFailure(f.owner.work) {
                f.owner.import(f.capture, f.graph.copy(compilations = f.graph.compilations.map { if (it.id == selected.id) changed else it }))
            }.code)
        }
    }

    @Test fun failedFullArgumentOrPluginCaptureKeepsAllContextsWithoutACompilationCertificate(): Unit {
        val f = fixture()
        val gap = ModelIssue("compiler_arguments_unmodeled", ":consumer||test",
            "compiler-arguments-create: actual strict task-configuration capture unavailable")
        val graph = f.graph.copy(issues = listOf(gap), compilations = f.graph.compilations.map {
            if (it.id == gap.scopeId) it.copy(compilerArguments = emptyList()) else it
        })
        val model = f.owner.import(f.capture, graph)
        assertEquals(4, model.compilationIds.size)
        model.compilationIds.forEach { id ->
            val context = model.context(id, f.owner.configurationDigest)
            assertEquals(ModelCompleteness.UNAVAILABLE, context.completeness)
            assertFalse(context.context!!.fullProject); assertNull(context.certificateId)
            assertContains(context.issues, gap)
        }
        assertEquals(model.compilationIds, ProjectImpactPlanner().impact(null, model).obligations.map { it.compilationId })
    }

    @Test fun unbuiltOutputStaysUnavailableAndEqualBytesCannotEnrollANewArtifact(): Unit {
        val f = fixture()
        val generated = f.owner.work.resolve("unregistered-derived/lib.jar")
        val missing = ModelArtifact("project::lib", generated.toString(), null, null)
        fun graph(artifact: ModelArtifact) = f.graph.copy(compilations = f.graph.compilations.map {
            if (it.id == ":consumer||main") it.copy(classpath = listOf(artifact)) else it
        })
        val unavailable = f.owner.import(f.capture, graph(missing)).context(":consumer||main", f.owner.configurationDigest)
        assertEquals(ModelCompleteness.UNAVAILABLE, unavailable.completeness); assertNull(unavailable.certificateId)
        Files.createDirectories(generated.parent)
        Files.copy(java.nio.file.Path.of(f.owner.artifact.path), generated)
        val sameBytes = missing.copy(digest = Digests.sha256(Files.readAllBytes(generated)), size = Files.size(generated))
        assertEquals(f.owner.artifact.digest, sameBytes.digest)
        assertFailsWith<IllegalStateException> { f.owner.import(f.capture, graph(sameBytes)) }
    }

    private data class Fixture(val owner: ModelFixtures, val capture: ModelFixtures.Capture, val graph: GradleProjectExport)

    private fun fixture(): Fixture {
        val owner = ModelFixtures()
        val files = linkedMapOf("build.gradle.kts" to "// fixed unit JVM plugin declaration\n",
            "settings.gradle.kts" to "// fixed unit module declaration\n")
        listOf("consumer", "lib").forEach { module ->
            files["$module/build.gradle.kts"] = "// fixed unit Kotlin JVM module\n"
            listOf("main", "test").forEach { name ->
                files["$module/src/$name/kotlin/${name.replaceFirstChar { it.uppercase() }}.kt"] = "fun ${module}${name}() = 1\n"
            }
            files["$module/src/main/resources/message.txt"] = "$module resource\n"
        }
        val capture = owner.capture(files)
        val byPath = capture.snapshot.files.associate { it.path to ModelFile(it.path, it.digest, it.size) }
        val sets = listOf(":consumer", ":lib").flatMap { module -> listOf("main", "test").map { name ->
            val prefix = "${module.removePrefix(":")}/src/$name"
            ModelSourceSet("$module|$name", module, name, emptyList(), listOf("$prefix/kotlin"), listOf("$prefix/resources"),
                byPath.values.filter { it.path.startsWith("$prefix/kotlin/") }, byPath.values.filter { it.path.startsWith("$prefix/resources/") })
        } }
        val compilations = sets.map { set ->
            val module = set.moduleId; val name = set.name
            ModelCompilation("$module||$name", module, "", "jvm", name, set.id, listOf(set.id), set.sources,
                "$module:compile${if (name == "main") "" else "Test"}Kotlin", "${if (name == "main") "compile" else "testCompile"}Classpath",
                listOf(owner.artifact), if (module == ":consumer") listOf(ModelProjectDependency(":lib", "apiElements", emptyMap(), ":lib||main")) else emptyList(),
                ModelToolchain("jvm", "2.4.21", listOf(owner.artifact), listOf(owner.artifact)), listOf("-language-version", "2.4"), "2.4", "2.4",
                producedVariants = if (name == "main") listOf("apiElements") else emptyList(),
                associatedCompilationIds = if (name == "test") listOf("$module||main") else emptyList())
        }
        val graph = GradleProjectExport(gradleVersion = "9.6.1", modules = listOf(ModelModule(":", "", "build.gradle.kts", emptyList())) +
            listOf(":consumer", ":lib").map { ModelModule(it, it.removePrefix(":"), "${it.removePrefix(":")}/build.gradle.kts", listOf("org.jetbrains.kotlin.jvm")) },
            sourceSets = sets, compilations = compilations, configurationInputs = byPath.values.filter { configurationPath(it.path) },
            generatedInputs = emptyList(), buildscriptArtifacts = listOf(owner.artifact), includedBuilds = emptyList(), issues = emptyList())
        return Fixture(owner, capture, graph)
    }

    private fun renameTargets(graph: GradleProjectExport, module: String, target: String): GradleProjectExport {
        val ids = graph.compilations.associate { it.id to if (it.moduleId == module) "$module|$target|${it.name}" else it.id }
        return graph.copy(compilations = graph.compilations.map { it.copy(id = ids.getValue(it.id), target = if (it.moduleId == module) target else it.target,
            associatedCompilationIds = it.associatedCompilationIds.map(ids::getValue),
            projectDependencies = it.projectDependencies.map { dep -> dep.copy(compilationId = dep.compilationId?.let(ids::getValue)) }) })
    }
}

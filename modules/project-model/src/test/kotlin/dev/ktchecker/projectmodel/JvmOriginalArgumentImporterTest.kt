package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.*
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Path
import kotlin.test.*

/** Synthetic owner-ingress controls, NOT a successful compiler installation/invocation.
 * The fixture compiler bytes deliberately do not match the semantic-normalization pin;
 * positive installed-runtime coverage belongs to the genuine owner acceptance harness. */
class JvmOriginalArgumentImporterTest {
    @Test fun defaultDenyRetainsRawArgumentsAndOriginalWholeGraphWithoutLayoutAuthority(): Unit {
        val f = Fixture(); val imported = f.import()
        val compilation = imported.compilation(f.compilation.id)!!
        assertEquals(f.compilation.compilerArguments, compilation.compilerArguments)
        assertEquals(f.compilation.compilerArguments, imported.context(compilation.id, f.owner.configurationDigest).context!!.compilerArguments)
        assertEquals(f.graph, imported.export)
        assertTrue(imported.issues.isEmpty())
        assertNull(imported.jvmArgumentLayout(compilation.id))
        assertEquals("model_jvm_argument_origin_unavailable", imported.jvmArgumentRejections[compilation.id])
    }

    @Test fun sameRegisteredOriginIsRecheckedTwiceButCannotConferCompilerPinAuthority(): Unit {
        val f = Fixture(); var checks = 0
        val imported = f.import { checks++; f.origin }
        assertEquals(2, checks)
        assertEquals("model_jvm_arguments_unsupported", imported.jvmArgumentRejections[f.compilation.id])
        assertNull(imported.jvmArgumentLayout(f.compilation.id))
        assertTrue(imported.issues.isEmpty(), "Executable layout eligibility is not a fabricated graph issue")
        assertEquals(f.compilation.compilerArguments, imported.context(f.compilation.id, f.owner.configurationDigest).context!!.compilerArguments)
    }

    @Test fun equalButNewOriginOrNullEquivocationAtSecondCheckRejectsIngress(): Unit {
        for (firstAbsent in listOf(false, true)) {
            val f = Fixture(); var checks = 0
            val failure = assertFailsWith<ProjectModelException> {
                f.import { if (++checks == 1) { if (firstAbsent) null else f.origin } else f.newOrigin() }
            }
            assertEquals("model_jvm_argument_origin_changed", failure.code)
        }
        val f = Fixture(); var checks = 0
        assertEquals("model_jvm_argument_origin_changed", assertFailsWith<ProjectModelException> {
            f.import { if (++checks == 1) f.origin else null }
        }.code)
    }

    @Test fun ownerOriginErrorsAreNeverConvertedToAnEmptyDomain(): Unit {
        val f = Fixture()
        assertEquals("fixture_owner_origin_failed", assertFailsWith<ProjectModelException> {
            f.import { throw ProjectModelException("fixture_owner_origin_failed", "Synthetic missing owner proof") }
        }.code)
    }

    @Test fun emptyUnselectedDomainAndExistingIssuesAreTruthfulNotRemovedForEligibility(): Unit {
        val f = Fixture(emptySourcesAndClasspath = true)
        val imported = f.import { f.origin }
        assertEquals(f.graph, imported.export)
        assertTrue(imported.compilation(f.compilation.id)!!.sourceFiles.isEmpty())
        assertTrue(imported.compilation(f.compilation.id)!!.classpath.isEmpty())
        assertNull(imported.compilation(f.compilation.id)!!.jvmArgumentRoles!!.classpathOperand)
        assertTrue(imported.issues.isEmpty())
        assertNull(imported.jvmArgumentLayout(f.compilation.id))
        val gap = ModelIssue("owner_declared_gap", f.compilation.id, "Synthetic retained issue; never normalized away")
        val g = Fixture(extraIssues = listOf(gap))
        assertTrue(gap in g.import { g.origin }.issues)
    }

    @Test fun roleDtoAndRejectionMapsAreDeeplyFrozenAndLegacyDoesNotCallNewAuthority(): Unit {
        val f = Fixture(); val imported = f.import { f.origin }
        assertFailsWith<UnsupportedOperationException> { (imported.compilation(f.compilation.id)!!.jvmArgumentRoles!!.sourcePaths as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (imported.jvmArgumentRejections as MutableMap<*, *>).clear() }
        val owner = ModelFixtures(); val capture = owner.capture(); val graph = owner.graph(capture)
        val raw = ContractJson.encodeToString(graph).toByteArray(); val request = owner.request(capture, graph, raw)
        val permit = ModelIngressPermit(request, owner.gradleRuntimeDigest, capture.verify, {}, {}, {},
            verifyJvmArgumentOrigin = { error("Absent legacy roles must not enroll an origin") })
        assumeOwnerFixtureAnchoredReads(owner.work)
        val legacy = ProjectModelImporter(ExactModelIngressAuthority(permit)).import(request, raw)
        assertTrue(legacy.jvmArgumentRejections.isEmpty())
        assertEquals(graph, legacy.export)
    }

    private class Fixture(emptySourcesAndClasspath: Boolean = false, extraIssues: List<ModelIssue> = emptyList()) {
        val owner = ModelFixtures()
        val capture: ModelFixtures.Capture
        val output: Path = owner.work.resolve("original-owner-output")
        val jdk: Path = owner.work.resolve("original-owner-jdk")
        val compilation: ModelCompilation
        val graph: GradleProjectExport
        val raw: ByteArray
        val request: ModelImportRequest
        val origin: ModelJvmArgumentOrigin
        init {
            assumeTrue(File.separator == "/", "The reviewed role adapter is explicitly POSIX-only")
            capture = owner.capture(if (emptySourcesAndClasspath) owner.files() - "independent/src/jvmMain/kotlin/Independent.kt" else owner.files())
            val original = owner.graph(capture)
            val old = original.compilations.single { it.moduleId == ":independent" }
            val id = ":independent||main"; val task = ":independent:compileKotlin"
            val destination = output.resolve("gradle-build/${Digests.sha256(":independent").removePrefix("sha256:")}/classes/kotlin/main").toString()
            val cp = if (emptySourcesAndClasspath) emptyList() else old.classpath
            val roles = ModelJvmArgumentRoles(compileTaskPath = task, sourcePaths = old.sourceFiles.map { it.path },
                classpathOperand = cp.takeIf { it.isNotEmpty() }?.joinToString(":") { it.path }, destinationPath = destination,
                selectedJdkHome = jdk.toString(), jdkHomeOperand = null, pluginDataState = "provider_absent")
            val args = listOf("-Xallow-no-source-files") + roles.classpathOperand?.let { listOf("-classpath", it) }.orEmpty() +
                listOf("-d", destination, "-jvm-target", "17", "-module-name", "synthetic_ingress", "-no-reflect", "-no-stdlib") +
                old.sourceFiles.map { capture.root.resolve(it.path).toString() }
            compilation = old.copy(id = id, target = "", compileTaskPath = task, classpath = cp, compilerArguments = args,
                languageVersion = null, apiVersion = null, jvmArgumentRoles = roles)
            graph = original.copy(modules = original.modules.map { module ->
                // The selected synthetic compilation is now ordinary JVM with an unnamed
                // target. Retain the existing importer plugin-domain gate rather than
                // asserting that an unchanged multiplatform module owns that identity.
                if (module.id == compilation.moduleId) module.copy(pluginIds = listOf("org.jetbrains.kotlin.jvm")) else module
            }, compilations = original.compilations.map { if (it.id == old.id) compilation else it }, issues = extraIssues)
            raw = ContractJson.encodeToString(graph).toByteArray(); request = owner.request(capture, graph, raw)
            origin = newOrigin()
        }
        fun newOrigin(): ModelJvmArgumentOrigin = ModelJvmArgumentOrigin(request, compilation.id, compilation.compileTaskPath,
            compilation.moduleId, compilation.name, output, jdk)
        fun import(verifyOrigin: (ModelCompilation) -> ModelJvmArgumentOrigin? = { null }): VerifiedProjectModel {
            assumeOwnerFixtureAnchoredReads(owner.work)
            val permit = ModelIngressPermit(request, owner.gradleRuntimeDigest, capture.verify, {}, { actual ->
                check(actual == owner.artifact)
            }, { actual -> check(actual == graph) }, verifyOrigin)
            return ProjectModelImporter(ExactModelIngressAuthority(permit)).import(request, raw)
        }
    }
}

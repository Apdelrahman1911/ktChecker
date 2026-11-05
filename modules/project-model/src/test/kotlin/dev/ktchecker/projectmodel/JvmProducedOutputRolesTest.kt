package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.*
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Path
import kotlin.test.*

/** PURE model-role/classifier controls. All artifacts and origins are synthetic declarations.
 * No importer grants from fake exact-pin bytes, installed binding, Gradle or compiler execution.
 * The separately opt-in RealProducedJvm owner is the connected real-byte acceptance experiment.
 */
class JvmProducedOutputRolesTest {
    @Test fun missingAssociatedOutputRemainsAnExplicitObligationAndRawArgvAbsence(): Unit {
        val f = data(friend = true, present = false)
        f.verify()
        val layout = f.layout()
        assertEquals(f.consumer.compilerArguments, layout.renderOriginal())
        assertFalse(layout.originalArguments.any { it.startsWith("-Xfriend-paths=") })
        assertEquals(f.consumer.classpath.drop(1).joinToString(":") { it.path }, f.consumer.jvmArgumentRoles!!.classpathOperand)
        assertNull(f.consumer.classpath.first().digest)
        val graph = assertNotNull(f.graphCapability())
        assertTrue(graph.reference.friend)
        assertFalse(graph.reference.originalClasspathPresent)
        assertFalse(graph.reference.originalFriendPresent)
        assertFalse(graph.reference.declaredFriendPresent)
        assertEquals(listOf(f.producer.id, f.consumer.id), graph.compilationIds)
        val model = f.valueModel(graph)
        val raw = model.context(f.consumer.id, f.request.configurationDigest)
        assertEquals(ModelCompleteness.UNAVAILABLE, raw.completeness)
        assertFalse(assertNotNull(raw.context).fullProject)
        assertEquals(f.consumer.compilerArguments, raw.context!!.compilerArguments)
        assertEquals(listOf(deferredJvmIssue(f.consumer.id, f.reference)), raw.issues)
    }

    @Test fun existingOutputStillHasNoImmutableByteClaimAndFriendTokenHasOneOriginalRole(): Unit {
        val f = data(friend = true, present = true)
        f.verify()
        val layout = f.layout()
        val token = layout.tokens.single { it.role == JvmArgumentTokenRole.PRODUCED_FRIEND_OPTION }
        assertEquals("-Xfriend-paths=${f.reference.outputPath}", token.original)
        assertEquals(f.consumer.compilerArguments, layout.renderOriginal())
        assertEquals(f.consumer.classpath.joinToString(":") { it.path }, f.consumer.jvmArgumentRoles!!.classpathOperand)
        assertNull(f.consumer.classpath.first().digest)
        assertNull(f.consumer.friendArtifacts.single().digest)
        assertEquals(f.reference.outputPath, f.consumer.friendArtifacts.single().path)
        assertNotNull(f.graphCapability())
    }

    @Test fun nonfriendEdgeNeverGetsAnAssociationOrOriginalFriendPermission(): Unit {
        val f = data(friend = false, present = false)
        f.verify()
        assertTrue(f.consumer.associatedCompilationIds.isEmpty())
        assertTrue(f.consumer.friendArtifacts.isEmpty())
        assertFalse(assertNotNull(f.graphCapability()).reference.friend)
        assertEquals(0, f.layout().tokens.count { it.role == JvmArgumentTokenRole.PRODUCED_FRIEND_OPTION })
        assertFailsWith<ProjectModelException> { f.verify(f.graph.copy(compilations = listOf(f.producer,
            f.consumer.copy(associatedCompilationIds = listOf(f.producer.id))))) }
        assertFailsWith<ProjectModelException> { f.layout(f.consumer.copy(compilerArguments =
            listOf("-Xfriend-paths=${f.reference.outputPath}") + f.consumer.compilerArguments)) }
    }

    @Test fun pendingExceptionCannotConcealAnyForeignArtifactOccurrenceEvenIfAlreadyHashed(): Unit {
        val f = data(friend = true, present = false)
        for (artifact in listOf(f.consumer.classpath.first(), f.consumer.classpath.first().copy(
            coordinate = "foreign:already-hashed", digest = Digests.sha256("not-produced"), size = 12))) {
            val chain = requireNotNull(f.consumer.toolchain)
            val variants = listOf(
                f.graph.copy(buildscriptArtifacts = listOf(artifact)),
                f.graph.copy(externalConfigurationArtifacts = listOf(artifact)),
                f.graph.copy(compilations = listOf(f.producer.copy(classpath = f.producer.classpath + artifact), f.consumer)),
                f.graph.copy(compilations = listOf(f.producer.copy(friendArtifacts = listOf(artifact)), f.consumer)),
                f.graph.copy(compilations = listOf(f.producer, f.consumer.copy(auxiliaryArtifacts = listOf(artifact)))),
                f.graph.copy(compilations = listOf(f.producer, f.consumer.copy(toolchain = chain.copy(compilerArtifacts = chain.compilerArtifacts + artifact)))),
                f.graph.copy(compilations = listOf(f.producer, f.consumer.copy(toolchain = chain.copy(runtimeArtifacts = listOf(artifact))))),
                f.graph.copy(compilations = listOf(f.producer, f.consumer.copy(toolchain = chain.copy(compilerPlugins = listOf(artifact))))),
            )
            variants.forEach { graph -> assertFailsWith<ProjectModelException> { f.verify(graph) } }
        }
        f.verify() // The exact consumer occurrence, and only it, remains admissible.
    }

    @Test fun wrongOrdinalTaskProducerAndCopiedOriginsCannotAuthorizeDeferredRoles(): Unit {
        val f = data(friend = true, present = false)
        val wrong = listOf(f.reference.copy(classpathIndex = 1), f.reference.copy(producerCompileTaskPath = ":other"),
            f.reference.copy(producerCompilationId = f.consumer.id), f.reference.copy(outputPath = "/synthetic/other-output"),
            f.reference.copy(originalClasspathPresent = true), f.reference.copy(friend = false))
        wrong.forEach { ref -> assertFailsWith<ProjectModelException> { f.verify(f.graph.copy(compilations =
            listOf(f.producer, f.consumer.copy(deferredJvmOutputs = listOf(ref))))) } }
        val copiedProducer = ModelJvmArgumentOrigin(f.request, f.producer.id, f.producer.compileTaskPath, ":", "main",
            Path.of("/synthetic/output"), Path.of("/synthetic/jdk"))
        assertFailsWith<ProjectModelException> { verifyDeferredJvmOrigin(f.request, f.graph, f.consumer, f.origin,
            mapOf(f.producer.id to copiedProducer, f.consumer.id to f.consumerOrigin)) }
        assertFailsWith<ProjectModelException> { verifyDeferredJvmOrigin(f.request, f.graph, f.consumer, f.origin, emptyMap()) }
    }

    @Test fun presentFactsCannotBeRepairedByDroppingOrAddingRawPathTokens(): Unit {
        val f = data(friend = true, present = true)
        val friendToken = "-Xfriend-paths=${f.reference.outputPath}"
        for (arguments in listOf(f.consumer.compilerArguments.filterNot { it == friendToken },
            listOf(friendToken) + f.consumer.compilerArguments,
            f.consumer.compilerArguments.map { if (it == friendToken) "-Xfriend-paths=/other" else it })) {
            assertFailsWith<ProjectModelException> { f.layout(f.consumer.copy(compilerArguments = arguments)) }
        }
        assertFailsWith<ProjectModelException> { ModelJvmProducedOutputOrigin(f.request, f.producerOrigin, f.consumerOrigin,
            0, false, true, originalFriendPresent = true, declaredFriendPresent = false) }
        val missing = data(friend = true, present = false)
        assertFailsWith<ProjectModelException> { missing.layout(missing.consumer.copy(jvmArgumentRoles =
            missing.consumer.jvmArgumentRoles!!.copy(classpathOperand = missing.consumer.classpath.joinToString(":") { it.path }))) }
    }

    @Test fun allExporterAndGlobalIssuesRemainBlockersAndExtraCompilationsAreNotDropped(): Unit {
        val f = data(friend = true, present = false)
        assertNotNull(f.graphCapability())
        for (issue in listOf(ModelIssue("artifact_unavailable", f.consumer.id, "Java output unknown."),
            ModelIssue("compiler_arguments_unmodeled", f.consumer.id, "Original args failed."),
            ModelIssue("unconsumed_source", null, "Unvisited input."))) {
            assertNull(f.graphCapability(issues = listOf(deferredJvmIssue(f.consumer.id, f.reference), issue)))
        }
        assertNull(f.graphCapability(f.graph.copy(compilations = f.graph.compilations + f.producer.copy(id = ":||third", name = "third"))))
        assertNull(f.graphCapability(f.graph.copy(compilations = listOf(f.producer.copy(sourceFiles = emptyList()), f.consumer))))
        assertNull(f.graphCapability(f.graph.copy(includedBuilds = listOf("included"))))
    }

    @Test fun javaResourceGeneratedPluginAndNativeDomainsCannotClaimThisExecutableSlice(): Unit {
        val f = data(friend = true, present = false)
        val chain = requireNotNull(f.consumer.toolchain)
        val resource = ModelArtifact("resources:output", "/synthetic/resources", null)
        val consumers = listOf(f.consumer.copy(auxiliaryInputFiles = listOf(ModelFile("src/test/Java.java", Digests.sha256("java"), 4))),
            f.consumer.copy(auxiliaryArtifacts = listOf(resource)),
            f.consumer.copy(toolchain = chain.copy(compilerPlugins = f.producer.classpath)),
            f.consumer.copy(platform = "native", toolchain = chain.copy(kind = "native")))
        consumers.forEach { assertNull(f.graphCapability(f.graph.copy(compilations = listOf(f.producer, it)))) }
        assertNull(f.graphCapability(f.graph.copy(sourceSets = f.graph.sourceSets.map { set ->
            if (set.name == "main") set.copy(resources = listOf(ModelFile("src/main/resources/config.json", Digests.sha256("{}"), 2))) else set
        })))
        assertNull(f.graphCapability(f.graph.copy(generatedInputs = listOf(GeneratedSourceInput(":generate", listOf(":|main"),
            emptyList(), f.producer.sourceFiles, listOf("generated"), "captured")))))
        val unexpectedOutput = f.consumer.copy(classpath = f.consumer.classpath + resource)
        assertFailsWith<ProjectModelException> { f.layout(unexpectedOutput) }
    }

    private data class Data(val request: ModelImportRequest, val producerOrigin: ModelJvmArgumentOrigin,
        val consumerOrigin: ModelJvmArgumentOrigin, val origin: ModelJvmProducedOutputOrigin,
        val producer: ModelCompilation, val consumer: ModelCompilation, val graph: GradleProjectExport) {
        val reference get() = origin.reference()
        fun verify(selected: GradleProjectExport = graph) = verifyDeferredJvmOrigin(request, selected,
            selected.compilations.single { it.id == consumer.id }, origin,
            mapOf(producer.id to producerOrigin, consumer.id to consumerOrigin))
        fun layout(selected: ModelCompilation = consumer) = classifyJvmArgumentLayout(request, selected, consumerOrigin, emptyList(), origin)
        fun graphCapability(selected: GradleProjectExport = graph,
            issues: List<ModelIssue> = listOf(deferredJvmIssue(consumer.id, reference))): VerifiedJvmProducedGraph? =
            verifiedJvmProducedGraph(request, selected, canonicalDigest(ContractJson.encodeToJsonElement(selected)), issues,
                mapOf(producer.id to classifyJvmArgumentLayout(request, producer, producerOrigin, emptyList()), consumer.id to layout()),
                mapOf(consumer.id to origin))
        fun valueModel(capability: VerifiedJvmProducedGraph): VerifiedProjectModel = VerifiedProjectModel(request.namespace,
            request.snapshot, graph, ProjectModelIdentity(request.snapshot.snapshotId, Digests.sha256("source"), capability.modelDigest,
                Digests.sha256("dependencies"), Digests.sha256("toolchain"), request.exporterDigest, Digests.sha256("gradle"), request.configurationDigest),
            listOf(deferredJvmIssue(consumer.id, reference)), Digests.sha256("SYNTHETIC value-model certificate"), jvmProducedGraph = capability)
    }

    private fun data(friend: Boolean, present: Boolean): Data {
        assumeTrue(File.separator == "/" && File.pathSeparator == ":", "Explicit POSIX argument-role profile")
        val files = listOf(ModelFile("src/main/kotlin/Api.kt", Digests.sha256("main"), 4),
            ModelFile("src/test/kotlin/Use.kt", Digests.sha256("test"), 4))
        val namespace = ModelNamespace("synthetic-owner", "synthetic-workspace", "synthetic-worktree", "synthetic-lineage")
        val snapshot = SnapshotManifest(Digests.sha256("synthetic-snapshot"), namespace.workspaceId, namespace.worktreeId,
            files.map { SnapshotFile(it.path, it.digest, it.size) }, namespace.ownerId)
        val request = ModelImportRequest(namespace, snapshot, "/synthetic/source", Digests.sha256("configuration"),
            Digests.sha256("export"), Digests.sha256("exporter"), "SYNTHETIC-data-only")
        val mainOrigin = ModelJvmArgumentOrigin(request, ":||main", ":compileKotlin", ":", "main", Path.of("/synthetic/output"), Path.of("/synthetic/jdk"))
        val testOrigin = ModelJvmArgumentOrigin(request, ":||test", ":compileTestKotlin", ":", "test", Path.of("/synthetic/output"), Path.of("/synthetic/jdk"))
        val origin = ModelJvmProducedOutputOrigin(request, mainOrigin, testOrigin, 0, present, friend, friend && present, friend && present)
        val reference = origin.reference()
        val external = ModelArtifact("data:stdlib:1", "/synthetic/dependencies/stdlib.jar", Digests.sha256("stdlib"), 6)
        val produced = ModelArtifact("data:deferred-main", reference.outputPath, null)
        val chain = ModelToolchain("jvm", "2.4.21", listOf(ModelArtifact("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21",
            "/synthetic/compiler/compiler.jar", VerifiedJvmArgumentLayout.COMPILER_DIGEST, 58_593_291)), emptyList())
        fun compilation(role: ModelJvmArgumentOrigin, source: ModelFile, consumer: Boolean): ModelCompilation {
            val cp = if (consumer) listOf(produced, external) else listOf(external)
            val roles = ModelJvmArgumentRoles(compileTaskPath = role.compileTaskPath, sourcePaths = listOf(source.path),
                classpathOperand = cp.filter { it.digest != null || present }.joinToString(":") { it.path },
                destinationPath = role.destinationPath.toString(), selectedJdkHome = role.selectedJdkHome.toString(),
                jdkHomeOperand = null, pluginDataState = "provider_absent",
                friendPathOperands = if (consumer && friend && present) listOf(reference.outputPath) else emptyList())
            val arguments = listOf("-classpath", roles.classpathOperand!!, "-d", roles.destinationPath,
                "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-module-name", role.compilationName) +
                (if (consumer && friend && present) listOf("-Xfriend-paths=${reference.outputPath}") else emptyList()) +
                listOf("/synthetic/source/${source.path}")
            return ModelCompilation(role.compilationId, ":", "", "jvm", role.compilationName, ":|${role.compilationName}",
                listOf(":|${role.compilationName}"), listOf(source), role.compileTaskPath, "${role.compilationName}Classpath", cp,
                emptyList(), chain, arguments, null, null, friendArtifacts = if (consumer && friend && present) listOf(produced) else emptyList(),
                associatedCompilationIds = if (consumer && friend) listOf(mainOrigin.compilationId) else emptyList(),
                jvmArgumentRoles = roles, deferredJvmOutputs = if (consumer) listOf(reference) else emptyList())
        }
        val main = compilation(mainOrigin, files[0], false); val test = compilation(testOrigin, files[1], true)
        val sourceSets = listOf(main, test).map { ModelSourceSet(it.defaultSourceSetId, ":", it.name, emptyList(),
            listOf("src/${it.name}/kotlin"), listOf("src/${it.name}/resources"), it.sourceFiles, emptyList()) }
        val graph = GradleProjectExport(gradleVersion = "9.6.1", modules = listOf(ModelModule(":", "", null, listOf("org.jetbrains.kotlin.jvm"))),
            sourceSets = sourceSets, compilations = listOf(main, test), configurationInputs = emptyList(), generatedInputs = emptyList(),
            buildscriptArtifacts = emptyList(), includedBuilds = emptyList(), issues = emptyList())
        return Data(request, mainOrigin, testOrigin, origin, main, test, graph)
    }
}

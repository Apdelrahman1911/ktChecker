package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** New opt-in connected fixtures, never modifications/repairs of an old owner attempt.
 * The friend case is genuinely nonempty main+test. The nonfriend control is truthfully a
 * DIFFERENT ordinary two-compilation project, configured before capture without associateWith.
 * Both deliberately select Kotlin output only through public task configuration: this does
 * not implement Java/resource-output support or silently erase exported gaps afterward.
 */
internal class RealProducedJvmFixture(val selection: String) {
    val friend = selection != NONFRIEND
    val phases = if (selection == FAILED) 1 else 2
    val consumerName = if (friend) "test" else "consumer"
    val consumerId = ":||$consumerName"
    val consumerTask = if (friend) ":compileTestKotlin" else ":compileConsumerKotlin"
    val consumerPath = "src/$consumerName/kotlin/Use.kt"
    val producerModule = "ktc_produced_producer"
    val consumerModule = "ktc_produced_${consumerName}_consumer"
    init { realExpect(selection in setOf(FRIEND_API, NONFRIEND, FAILED), "real_produced_fixture_unknown") }

    fun files(phase: Int): Map<String, String> {
        realExpect(phase in 1..phases, "real_produced_fixture_phase")
        val declarations = when {
            selection == FAILED -> "package produced\n\nfun publicValue(): String = missingProducerValue()\n"
            selection == FRIEND_API && phase == 2 -> API_AFTER
            else -> API_BEFORE
        }
        val use = when {
            selection == FRIEND_API -> FRIEND_USE
            selection == NONFRIEND && phase == 2 -> NONFRIEND_USE
            else -> PUBLIC_USE
        }
        return sortedMapOf("settings.gradle" to """
            pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
            rootProject.name = 'ktc_real_produced_$selection'
        """.trimIndent() + "\n", "build.gradle" to build(),
            "gradle.properties" to "org.gradle.java.installations.auto-detect=false\norg.gradle.java.installations.auto-download=false\n",
            PRODUCER_PATH to declarations, consumerPath to use)
    }

    private fun build(): String {
        val chooseConsumer = if (friend) "def consumer = actualCompilations.getByName('test')" else """
            // Distinct nonfriend control, NOT ordinary main/test support or an empty-test pass.
            def oldTest = actualCompilations.getByName('test')
            if (!actualCompilations.remove(oldTest)) throw new GradleException('Nonfriend control test removal rejected')
            def consumer = actualCompilations.create('consumer')
        """.trimIndent()
        return """
            import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
            import org.jetbrains.kotlin.gradle.dsl.JvmTarget
            plugins { id 'org.jetbrains.kotlin.jvm' version '2.4.21' }
            repositories { mavenCentral() }
            kotlin { jvmToolchain(21) }
            def actualCompilations = kotlin.target.compilations
            def producer = actualCompilations.getByName('main')
            $chooseConsumer
            def producerTask = producer.compileTaskProvider.get()
            def consumerTask = consumer.compileTaskProvider.get()
            def external = configurations.getByName(consumer.compileDependencyConfigurationName)
            def producerOutput = producer.compileTaskProvider.flatMap { it.destinationDirectory }
            // BEFORE export, select the actual Kotlin task output plus complete external config.
            // No source merging, reflected report edits, Java/resource placeholders or output guesses.
            consumerTask.libraries.setFrom(producerOutput, external)
            consumerTask.libraries.disallowChanges()
            consumerTask.friendPaths.setFrom(${if (friend) "producerOutput" else "[]"})
            consumerTask.friendPaths.disallowChanges()
            tasks.withType(KotlinCompile).configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
            producerTask.compilerOptions.moduleName.set('$producerModule')
            consumerTask.compilerOptions.moduleName.set('$consumerModule')
            gradle.projectsEvaluated {
                if (actualCompilations.toList().collect { it.name }.sort() != ['main', '$consumerName'].sort())
                    throw new GradleException('Connected compilation domain changed')
                if (!producer.allAssociatedCompilations.isEmpty()) throw new GradleException('Producer has unexpected association')
                def associations = consumer.allAssociatedCompilations.toList()
                if (${if (friend) "associations.size() != 1 || !associations[0].is(producer)" else "!associations.isEmpty()"})
                    throw new GradleException('Actual friend association differs from selected control')
                def output = producerTask.destinationDirectory.get().asFile
                if (output.exists()) throw new GradleException('Fresh exporter cannot borrow preexisting produced output')
                if (consumerTask.libraries.files.toList() != [output] + external.files.toList())
                    throw new GradleException('Whole actual task classpath edge/order differs')
                if (consumerTask.friendPaths.files.toList() != ${if (friend) "[output]" else "[]"})
                    throw new GradleException('Actual friend path vector differs')
                if (producerTask.sources.asFileTree.files.isEmpty() || consumerTask.sources.asFileTree.files.isEmpty())
                    throw new GradleException('Both connected compilations must be genuinely nonempty')
                tasks.withType(KotlinCompile).configureEach {
                    pluginClasspath.setFrom([])
                    pluginClasspath.disallowChanges()
                }
            }
        """.trimIndent() + "\n"
    }

    fun manifest(phase: Int): List<ModelFile> = files(phase).map { (path, text) ->
        val bytes = text.toByteArray(Charsets.UTF_8); ModelFile(path, Digests.sha256(bytes), bytes.size.toLong())
    }
    fun verifySnapshot(snapshot: SnapshotManifest, phase: Int) {
        realExpect(snapshot.files.map { ModelFile(it.path, it.digest, it.size) } == manifest(phase) &&
            snapshot.scopeExclusions.isEmpty() && snapshot.unknownInputs.isEmpty() &&
            snapshot.declaredRoots == listOf("project") && snapshot.captureKind == "immutable_overlay",
            "real_produced_snapshot_changed")
    }

    /** Constants originate in the exact captured pre-execution build above, not in raw report fields.
     * The successful exporter must have executed all its actual task-container assertions, and
     * the pinned exporter independently checks these task/output roles again. Retain ALL issues.
     */
    fun origins(request: ModelImportRequest, output: Path, inputs: RealModelOwnerInputs): RealProducedJvmOrigins {
        val producer = ModelJvmArgumentOrigin(request, PRODUCER_ID, PRODUCER_TASK, ":", "main", output, inputs.javaHome)
        val consumer = ModelJvmArgumentOrigin(request, consumerId, consumerTask, ":", consumerName, output, inputs.javaHome)
        realExpect(!Files.exists(producer.destinationPath, NOFOLLOW_LINKS) && !Files.exists(consumer.destinationPath, NOFOLLOW_LINKS),
            "real_produced_export_must_not_compile")
        return RealProducedJvmOrigins(producer, consumer,
            ModelJvmProducedOutputOrigin(request, producer, consumer, 0, false, friend, friend, friend))
    }

    fun verifyGraph(snapshot: SnapshotManifest, phase: Int, graph: GradleProjectExport,
        inputs: RealModelOwnerInputs, installation: KotlinJvmToolchain, output: Path, origins: RealProducedJvmOrigins) {
        verifySnapshot(snapshot, phase)
        realExpect(graph.gradleVersion == "9.6.1" && graph.modules.size == 1, "real_produced_module_domain")
        val module = graph.modules.single()
        realExpect(module.id == ":" && module.directory.isEmpty() && module.buildFile == "build.gradle" &&
            "org.jetbrains.kotlin.jvm" in module.pluginIds && module.pluginIds.none {
                it == "org.jetbrains.kotlin.multiplatform" || it == "org.jetbrains.kotlin.android" || it.startsWith("com.android.")
            }, "real_produced_platform_domain")
        val captured = manifest(phase).associateBy { it.path }
        realExpect(graph.configurationInputs == captured.values.filter { !it.path.endsWith(".kt") } &&
            graph.includedBuilds.isEmpty() && graph.generatedInputs.isEmpty(), "real_produced_extra_input_domain")
        realExpect(graph.compilations.map { it.id }.toSet() == setOf(PRODUCER_ID, consumerId) && graph.compilations.size == 2,
            "real_produced_exact_two_nonempty_compilations")
        val expectedNames = if (friend) setOf("main", "test") else setOf("main", "test", "consumer")
        realExpect(graph.sourceSets.map { it.name }.distinct().size == graph.sourceSets.size &&
            graph.sourceSets.map { it.name }.containsAll(listOf("main", consumerName)) &&
            graph.sourceSets.all { it.name in expectedNames }, "real_produced_source_set_domain")
        graph.sourceSets.forEach { set ->
            val expected = when (set.name) {
                "main" -> listOf(captured.getValue(PRODUCER_PATH))
                consumerName -> listOf(captured.getValue(consumerPath))
                else -> emptyList()
            }
            realExpect(set.id == ":|${set.name}" && set.moduleId == ":" && set.dependsOn.isEmpty() &&
                set.sourceRoots == listOf("src/${set.name}/kotlin") && set.resourceRoots == listOf("src/${set.name}/resources") &&
                set.sources == expected && set.resources.isEmpty(), "real_produced_complete_source_domain")
        }
        graph.compilations.forEach { compilation ->
            val isProducer = compilation.id == PRODUCER_ID
            val name = if (isProducer) "main" else consumerName
            val origin = if (isProducer) origins.producer else origins.consumer
            realExpect(compilation.moduleId == ":" && compilation.target.isEmpty() && compilation.platform == "jvm" &&
                compilation.name == name && compilation.defaultSourceSetId == ":|$name" &&
                compilation.sourceSetIds == listOf(":|$name") && compilation.compileTaskPath == origin.compileTaskPath &&
                compilation.sourceFiles == listOf(captured.getValue(if (isProducer) PRODUCER_PATH else consumerPath)) &&
                compilation.projectDependencies.isEmpty() && compilation.auxiliaryArtifacts.isEmpty() && compilation.auxiliaryInputFiles.isEmpty(),
                "real_produced_compilation_domain")
            val chain = requireNotNull(compilation.toolchain)
            realExpect(chain.kind == "jvm" && chain.compilerVersion == "2.4.21" && chain.compilerPlugins.isEmpty() &&
                chain.runtimeArtifacts.map { it.path }.sorted() == inputs.jdkPaths &&
                chain.compilerArtifacts.map { ArtifactIdentity(it.coordinate, requireNotNull(it.digest), it.path) } == installation.compilerClasspath &&
                chain.compilerArtifacts.map { it.size } == RealModelRuntimeRoster.compilerArtifacts.map { it.bytes },
                "real_produced_exact_real_installation")
            // Expected DATA follows the exporter's actual task-classpath label, not the compiler
            // roster's Maven label. Paths/bytes/order still come ONLY from the pre-enrolled pins.
            val external = listOf(inputs.selectedDependency(8), inputs.selectedDependency(9)).map { artifact ->
                artifact.copy(coordinate = "resolved:${Path.of(artifact.path).fileName}")
            }
            val pending = ModelJvmProducedOutputReference(PRODUCER_ID, PRODUCER_TASK, origins.producer.destinationPath.toString(),
                0, false, friend, friend, friend)
            realExpect(compilation.deferredJvmOutputs == if (isProducer) emptyList<ModelJvmProducedOutputReference>() else listOf(pending),
                "real_produced_edge_mismatch")
            realExpect(compilation.associatedCompilationIds == if (!isProducer && friend) listOf(PRODUCER_ID) else emptyList<String>(),
                "real_produced_association_mismatch")
            realExpect(compilation.friendArtifacts.map { it.path } == if (!isProducer && friend) listOf(pending.outputPath) else emptyList<String>(),
                "real_produced_friend_mismatch")
            realExpect(compilation.classpath == if (isProducer) external else listOf(compilation.classpath.first()) + external,
                "real_produced_external_classpath_order")
            if (!isProducer) realExpect(compilation.classpath.first().let { it.path == pending.outputPath && it.digest == null && it.size == null },
                "real_produced_output_not_deferred")
            val roles = requireNotNull(compilation.jvmArgumentRoles)
            realExpect(roles.destinationPath == origin.destinationPath.toString() && roles.selectedJdkHome == inputs.javaHome.toString() &&
                roles.classpathOperand == external.joinToString(":") { it.path } &&
                roles.friendPathOperands == if (!isProducer && friend) listOf(pending.outputPath) else emptyList<String>(),
                "real_produced_raw_argument_roles_changed")
            val moduleOption = compilation.compilerArguments.indexOf("-module-name")
            realExpect(moduleOption >= 0 && compilation.compilerArguments.getOrNull(moduleOption + 1) ==
                if (isProducer) producerModule else consumerModule, "real_produced_distinct_compiler_modules_required")
        }
        val exporter = inputs.exporterArtifact(output)
        realExpect(graph.externalConfigurationArtifacts.size == 1 && graph.externalConfigurationArtifacts.single().let {
            it.path == exporter.path && it.digest == exporter.digest && it.size == exporter.size
        }, "real_produced_exporter_configuration_changed")
        graph.buildscriptArtifacts.filter { it.digest != null }.forEach(inputs::verifyArtifact)
    }

    companion object {
        const val FRIEND_API = "friend_api"
        const val NONFRIEND = "nonfriend"
        const val FAILED = "failed_producer"
        const val PRODUCER_ID = ":||main"
        const val PRODUCER_TASK = ":compileKotlin"
        const val PRODUCER_PATH = "src/main/kotlin/Api.kt"
        const val API_BEFORE = "package produced\n\ninternal fun internalValue(): String = \"internal\"\nprivate fun privateValue(): String = \"private\"\nfun publicValue(): String = \"public\"\n"
        const val API_AFTER = "package produced\n\ninternal fun internalValue(): String = \"internal\"\nprivate fun privateValue(): String = \"private\"\nfun replacementPublicValue(): String = \"public\"\n"
        const val FRIEND_USE = "package produced\n\nfun usePublic(): String = publicValue()\nfun useInternal(): String = internalValue()\n"
        const val PUBLIC_USE = "package produced\n\nfun usePublic(): String = publicValue()\n"
        const val NONFRIEND_USE = "package produced\n\nfun usePublic(): String = publicValue()\nfun useInternal(): String = internalValue()\nfun usePrivate(): String = privateValue()\n"
    }
}

internal class RealProducedJvmOrigins(val producer: ModelJvmArgumentOrigin, val consumer: ModelJvmArgumentOrigin,
    val edge: ModelJvmProducedOutputOrigin)

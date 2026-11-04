package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

/** Synthetic importer/installation relation tests, not Gradle/compiler execution or qualification. */
class KotlinJvmModelBindingTest {
    @Test fun liveBindingRetainsThreeDistinctIdentitiesAndExactOriginalOrder(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val model = f.importModel()
        val before = ContractJson.encodeToString(model.export)
        val context = assertNotNull(model.context(f.compilationId, f.configuration).context)
        val binding = KotlinJvmModelBinding.verifyAndBind(model, f.compilationId, f.installation)
        assertSame(model, binding.model)
        assertEquals(f.compilationId, binding.compilationId)
        assertEquals(3, setOf(model.identity.toolchainDigest, context.toolchainDigest, f.installation.digest).size)
        assertEquals(context.toolchainDigest, binding.identity.modelToolchainDigest)
        assertEquals(f.installation.digest, binding.identity.installedToolchainDigest)
        assertEquals(f.installation.runtimeImage.digest, binding.identity.runtimeImageDigest)
        assertEquals(bridgeTestDigest(context), binding.identity.modelContextDigest)
        assertEquals("customJvm", context.scope.target)
        assertEquals(listOf("src/main/kotlin/B.kt", "src/main/kotlin/A.kt"), context.compilationFiles)
        assertEquals(model.export.compilations.single().compilerArguments, context.compilerArguments)
        assertEquals(f.dependencies.map { it.path }, context.classpath.map { it.path })
        assertNotEquals(f.compilerArtifacts.first().coordinate, f.installation.compilerClasspath.first().coordinate)
        assertEquals(before, ContractJson.encodeToString(model.export))
        binding.verifyInstallation()
    }

    @Test fun compilerBijectionRejectsOrderMissingExtraAndEqualByteRelocation(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val graph = f.graph()
        val compilation = graph.compilations.single()
        val chain = assertNotNull(compilation.toolchain)
        val extra = f.artifact("compiler/extra.jar", "extra non-executable support", "unit:extra:1")
        val relocated = f.artifact("compiler/elsewhere/kotlin-compiler-embeddable-2.4.21.jar",
            Files.readString(Path.of(chain.compilerArtifacts.first().path)), chain.compilerArtifacts.first().coordinate)
        val candidates = listOf(chain.compilerArtifacts.reversed(), chain.compilerArtifacts.take(1),
            chain.compilerArtifacts + extra, listOf(relocated) + chain.compilerArtifacts.drop(1))
        candidates.forEach { artifacts ->
            val model = f.importModel(graph.copy(compilations = listOf(compilation.copy(toolchain = chain.copy(compilerArtifacts = artifacts)))))
            expectModelFailure("compiler_model_compiler_artifacts_mismatch") {
                KotlinJvmModelBinding.verifyAndBind(model, f.compilationId, f.installation)
            }
        }
    }

    @Test fun filenameCannotSubstituteResolvedCompilerCoordinateOrPinnedVersion(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val graph = f.graph()
        val compilation = graph.compilations.single()
        val chain = assertNotNull(compilation.toolchain)
        val coordinates = chain.compilerArtifacts.map { it.copy(coordinate = "filename:${Path.of(it.path).fileName}") }
        val unnamed = f.importModel(graph.copy(compilations = listOf(compilation.copy(toolchain = chain.copy(compilerArtifacts = coordinates)))))
        expectModelFailure("compiler_model_compiler_coordinate_unverified") {
            KotlinJvmModelBinding.verifyAndBind(unnamed, f.compilationId, f.installation)
        }
        val wrongVersion = f.importModel(graph.copy(compilations = listOf(compilation.copy(toolchain = chain.copy(compilerVersion = "2.4.20")))))
        expectModelFailure("compiler_model_version_unsupported") {
            KotlinJvmModelBinding.verifyAndBind(wrongVersion, f.compilationId, f.installation)
        }
    }

    @Test fun runtimeRequiresWholeUniqueFileDomainAndExporterVocabulary(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val graph = f.graph()
        val compilation = graph.compilations.single()
        val chain = assertNotNull(compilation.toolchain)
        val extra = f.artifact("outside-runtime/extra", "not part of selected image", "compilation-jdk:extra")
        val candidates = listOf(chain.runtimeArtifacts.dropLast(1), chain.runtimeArtifacts + extra,
            chain.runtimeArtifacts + chain.runtimeArtifacts.first(),
            chain.runtimeArtifacts.map { it.copy(coordinate = it.coordinate.replace("compilation-jdk:", "inferred-jdk:")) })
        candidates.forEach { artifacts ->
            val model = f.importModel(graph.copy(compilations = listOf(compilation.copy(toolchain = chain.copy(runtimeArtifacts = artifacts)))))
            expectModelFailure("compiler_model_runtime_domain_mismatch") {
                KotlinJvmModelBinding.verifyAndBind(model, f.compilationId, f.installation)
            }
        }
    }

    @Test fun equalRuntimeBytesAtAnotherPathAreNotSelectedImageEntries(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val graph = f.graph()
        val compilation = graph.compilations.single()
        val chain = assertNotNull(compilation.toolchain)
        val first = chain.runtimeArtifacts.first()
        val replacement = f.artifact("outside-runtime/same-bytes", Files.readString(Path.of(first.path)), first.coordinate)
        val model = f.importModel(graph.copy(compilations = listOf(compilation.copy(toolchain = chain.copy(
            runtimeArtifacts = listOf(replacement) + chain.runtimeArtifacts.drop(1))))))
        expectModelFailure("compiler_model_runtime_artifact_mismatch") {
            KotlinJvmModelBinding.verifyAndBind(model, f.compilationId, f.installation)
        }
    }

    @Test fun runtimeFileRecordOrderRemainsInModelRatherThanBecomingClasspathOrder(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val graph = f.graph()
        val compilation = graph.compilations.single()
        val chain = assertNotNull(compilation.toolchain)
        val reversed = f.importModel(graph.copy(compilations = listOf(compilation.copy(toolchain = chain.copy(runtimeArtifacts = chain.runtimeArtifacts.reversed())))))
        val binding = KotlinJvmModelBinding.verifyAndBind(reversed, f.compilationId, f.installation)
        assertEquals(chain.runtimeArtifacts.reversed(), binding.compilation.toolchain!!.runtimeArtifacts)
        assertEquals(f.installation.runtimeImage.digest, binding.identity.runtimeImageDigest)
        assertEquals(f.compilerArtifacts, binding.compilation.toolchain!!.compilerArtifacts)
    }

    @Test fun actualPlatformAndToolchainKindCannotBeSpoofedByJvmTargetLabels(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val graph = f.graph()
        val compilation = graph.compilations.single()
        val chain = assertNotNull(compilation.toolchain)
        for ((platform, kind) in listOf("native" to "native", "androidJvm" to "jvm", "jvm" to "native",
                "common" to "metadata", "wasm" to "wasm")) {
            val model = f.importModel(graph.copy(compilations = listOf(compilation.copy(target = "jvm", platform = platform,
                toolchain = chain.copy(kind = kind)))))
            expectModelFailure("compiler_model_platform_unsupported") {
                KotlinJvmModelBinding.verifyAndBind(model, f.compilationId, f.installation)
            }
        }
    }

    @Test fun pluginFriendAuxiliaryAndAssociationSemanticsAreNotGuessed(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val graph = f.graph()
        val compilation = graph.compilations.single()
        val chain = assertNotNull(compilation.toolchain)
        val variants = listOf(compilation.copy(toolchain = chain.copy(compilerPlugins = listOf(f.dependencies.first()))),
            compilation.copy(friendArtifacts = listOf(f.dependencies.first())),
            compilation.copy(auxiliaryArtifacts = listOf(f.dependencies.first())),
            compilation.copy(auxiliaryInputFiles = listOf(graph.configurationInputs.first())))
        variants.forEach { modified ->
            val model = f.importModel(graph.copy(compilations = listOf(modified)))
            expectModelFailure("compiler_model_auxiliary_inputs_unsupported") {
                KotlinJvmModelBinding.verifyAndBind(model, f.compilationId, f.installation)
            }
        }
        val sibling = compilation.copy(id = ":fixture|customJvm|test", name = "test", compileTaskPath = ":fixture:compileTestKotlin")
        val associated = f.importModel(graph.copy(compilations = listOf(compilation.copy(associatedCompilationIds = listOf(sibling.id)), sibling)))
        expectModelFailure("compiler_model_auxiliary_inputs_unsupported") {
            KotlinJvmModelBinding.verifyAndBind(associated, f.compilationId, f.installation)
        }
    }

    @Test fun completeRefinementGraphIsStillUnsupportedWithoutSourceRoleLowering(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val graph = f.graph()
        val base = graph.sourceSets.single()
        val common = base.copy(id = ":fixture|common", name = "common", sources = listOf(base.sources.first()), resources = emptyList())
        val leaf = base.copy(dependsOn = listOf(common.id), sources = base.sources.drop(1))
        val compilation = graph.compilations.single().copy(sourceSetIds = listOf(leaf.id, common.id))
        val model = f.importModel(graph.copy(sourceSets = listOf(common, leaf), compilations = listOf(compilation)))
        assertEquals(ModelCompleteness.COMPLETE, model.context(f.compilationId, f.configuration).completeness)
        expectModelFailure("compiler_model_source_roles_unsupported") {
            KotlinJvmModelBinding.verifyAndBind(model, f.compilationId, f.installation)
        }
    }

    @Test fun unknownAndPathArgumentsAreRejectedWithoutStrippingOriginalTokens(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val graph = f.graph()
        val compilation = graph.compilations.single()
        for (arguments in listOf(listOf("-d", "/unapproved/output"), listOf("-classpath", "/unapproved/dependency.jar"),
                listOf("@response.txt"), listOf("-language-version=2.4"), listOf("-Xcommon-sources=a.kt"),
                listOf("-Xmulti-platform"), listOf("-no-stdlib"), listOf("-module-name", "../escape"), listOf("-unknown"))) {
            val model = f.importModel(graph.copy(compilations = listOf(compilation.copy(compilerArguments = arguments))))
            expectModelFailure("compiler_model_arguments_unsupported") {
                KotlinJvmModelBinding.verifyAndBind(model, f.compilationId, f.installation)
            }
            assertEquals(arguments, model.compilation(f.compilationId)!!.compilerArguments)
        }
        val mismatch = f.importModel(graph.copy(compilations = listOf(compilation.copy(languageVersion = "2.3"))))
        expectModelFailure("compiler_model_language_options_mismatch") {
            KotlinJvmModelBinding.verifyAndBind(mismatch, f.compilationId, f.installation)
        }
    }

    @Test fun anotherActualContextGapCannotBeHiddenByBindingOnlyHealthyCompilation(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val graph = f.graph()
        val sibling = graph.compilations.single().copy(id = ":fixture|customJvm|test", name = "test", compileTaskPath = ":fixture:compileTestKotlin")
        val model = f.importModel(graph.copy(compilations = graph.compilations + sibling,
            issues = listOf(ModelIssue("unsupported-sibling-input", sibling.id, "A synthetic sibling gap, not silently discharged."))))
        assertEquals(2, model.compilationIds.size)
        expectModelFailure("compiler_model_incomplete") {
            KotlinJvmModelBinding.verifyAndBind(model, f.compilationId, f.installation)
        }
        expectModelFailure("compiler_model_compilation_unavailable") {
            KotlinJvmModelBinding.verifyAndBind(model, ":not-exported", f.installation)
        }
    }

    @Test fun finalPreparationCannotRewriteAnyModelContextOrIngressPolicy(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val provider = KotlinJvmCompilerProvider(f.installation, inputMode = KotlinJvmInputMode.VERIFIED_MODEL)
        val preparation = f.prepare(provider)
        val input = preparation.input
        val context = input.context
        val altered = listOf(context.copy(scope = context.scope.copy(target = "jvm")), context.copy(compilerVersion = "2.4.20"),
            context.copy(classpath = context.classpath.reversed()), context.copy(compilerArguments = context.compilerArguments + "-nowarn"),
            context.copy(compilationFiles = context.compilationFiles!!.reversed()), context.copy(libraries = mapOf("invented" to "library")),
            context.copy(modelDigest = Digests.sha256("foreign-model")), context.copy(dependencyDigest = Digests.sha256("foreign-dependencies")),
            context.copy(configurationDigest = Digests.sha256("foreign-policy")), context.copy(toolchainDigest = f.installation.digest),
            context.copy(fullProject = false), context.copy(policies = context.policies - "ktchecker.model"),
            context.copy(policies = context.policies + ("ktchecker.model-certificate" to Digests.sha256("forged"))),
            context.copy(policies = context.policies + ("arbitrary-owner-assertion" to "not a permitted addition")))
        altered.forEach { value ->
            expectModelFailure("compiler_model_context_mismatch") {
                preparation.binding.bindPreparedInput(input.copy(context = value), listOf(f.rule), provider, preparation.profiles)
            }
        }
        val notOriginal = listOf(input.copy(snapshot = input.snapshot.copy(snapshotId = Digests.sha256("another-snapshot"))),
            input.copy(execution = preparation.prepared.compilerProfile), input.copy(outputRoot = f.work.resolve("output").toString()))
        notOriginal.forEach { value -> expectModelFailure("compiler_model_input_mismatch") {
            preparation.binding.bindPreparedInput(value, listOf(f.rule), provider, preparation.profiles)
        } }
        val otherIngress = KotlinJvmModelBinding.verifyAndBind(f.importModel(), f.compilationId, f.installation)
        expectModelFailure("compiler_model_context_mismatch") {
            otherIngress.bindPreparedInput(input, listOf(f.rule), provider, preparation.profiles)
        }
    }

    @Test fun finalPreparationBindsCompleteProfileMapAndRetainsFullParentRuleVector(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val provider = KotlinJvmCompilerProvider(f.installation, inputMode = KotlinJvmInputMode.VERIFIED_MODEL)
        val preparation = f.prepare(provider)
        val input = preparation.input
        assertEquals(2, preparation.prepared.original.requiredRuleIds.size)
        assertEquals(listOf(f.rule), preparation.prepared.compilerRuleIds)
        expectModelFailure("compiler_model_preparation_incomplete") {
            preparation.binding.bindPreparedInput(input.copy(context = input.context.copy(policies = input.context.policies - "ktchecker.activation-plan")),
                listOf(f.rule), provider, preparation.profiles)
        }
        val fullProfiles = preparation.profiles + ("another-retained-provider" to preparation.prepared.compilerProfile)
        expectModelFailure("compiler_model_profiles_mismatch") {
            preparation.binding.bindPreparedInput(input, listOf(f.rule), provider, fullProfiles)
        }
        val complete = withBridgeTestProfiles(input, fullProfiles)
        val retained = preparation.binding.bindPreparedInput(complete, listOf(f.rule), provider, fullProfiles)
        assertEquals(input.requiredRuleIds, retained.original.requiredRuleIds)
        assertEquals(2, fullProfiles.size)
        val changedProfile = preparation.prepared.compilerProfile.copy(arguments = preparation.prepared.compilerProfile.arguments + "unapproved")
        val changedProfiles = mapOf(provider.id to changedProfile)
        expectModelFailure("compiler_model_profile_mismatch") {
            preparation.binding.bindPreparedInput(withBridgeTestProfiles(input, changedProfiles), listOf(f.rule), provider, changedProfiles)
        }
        expectModelFailure("compiler_model_profile_missing") {
            preparation.binding.bindPreparedInput(withBridgeTestProfiles(input, emptyMap()), listOf(f.rule), provider, emptyMap())
        }
        for (rules in listOf(emptyList(), listOf(f.rule, f.rule), listOf("catalog.required-but-not-implemented"))) {
            expectModelFailure("compiler_model_rule_projection_invalid") {
                preparation.binding.bindPreparedInput(input, rules, provider, preparation.profiles)
            }
        }
    }

    @Test fun preparationRequiresSameInstallationObjectAndModelOnlyProvider(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val provider = KotlinJvmCompilerProvider(f.installation, inputMode = KotlinJvmInputMode.VERIFIED_MODEL)
        val preparation = f.prepare(provider)
        val sameBytes = KotlinJvmToolchain(f.java, f.installation.compilerClasspath)
        assertEquals(f.installation.digest, sameBytes.digest)
        val alternatives = listOf(KotlinJvmCompilerProvider(f.installation),
            KotlinJvmCompilerProvider(sameBytes, inputMode = KotlinJvmInputMode.VERIFIED_MODEL))
        alternatives.forEach { other -> expectModelFailure("compiler_model_provider_mismatch") {
            preparation.binding.bindPreparedInput(preparation.input, listOf(f.rule), other, preparation.profiles)
        } }
    }

    @Test fun preparedInputAndProfilesDetachAllMutableCollectionAliases(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val provider = KotlinJvmCompilerProvider(f.installation, inputMode = KotlinJvmInputMode.VERIFIED_MODEL)
        val preparation = f.prepare(provider)
        val files = preparation.input.snapshot.files.toMutableList()
        val rules = preparation.input.requiredRuleIds.toMutableList()
        val arguments = preparation.input.context.compilerArguments.toMutableList()
        val policies = preparation.input.context.policies.toMutableMap()
        val profileArguments = preparation.prepared.compilerProfile.arguments.toMutableList()
        val profiles = mutableMapOf(provider.id to preparation.prepared.compilerProfile.copy(arguments = profileArguments))
        val copied = preparation.input.copy(snapshot = preparation.input.snapshot.copy(files = files), requiredRuleIds = rules,
            context = preparation.input.context.copy(compilerArguments = arguments, policies = policies))
        val retained = preparation.binding.bindPreparedInput(copied, listOf(f.rule), provider, profiles)
        files.clear(); rules.clear(); arguments.clear(); policies.clear(); profileArguments.clear(); profiles.clear()
        assertEquals(preparation.input, retained.original)
        assertEquals(preparation.prepared.compilerProfile, retained.compilerProfile)
        assertFailsWith<UnsupportedOperationException> { (retained.original.requiredRuleIds as MutableList<String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (retained.original.context.policies as MutableMap<String, String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (retained.compilerProfile.arguments as MutableList<String>).clear() }
        assertEquals(preparation.input.analysisKey(), retained.originalAnalysisKey)
    }

    @Test fun equalByteCompilerParentAliasCannotReplaceInitiallyAnchoredModelLocations(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        assumeTrue("posix" in f.work.fileSystem.supportedFileAttributeViews(), "Compiler-parent symlink case requires a symlink-capable fixture filesystem")
        val model = f.importModel()
        val binding = KotlinJvmModelBinding.verifyAndBind(model, f.compilationId, f.installation)
        val parent = f.work.resolve("compiler")
        val replacement = Files.createDirectory(f.work.resolve("equal-byte-compiler"))
        f.installation.compilerClasspath.forEach { artifact ->
            Files.copy(Path.of(artifact.path), replacement.resolve(Path.of(artifact.path).fileName))
        }
        val saved = Files.move(parent, f.work.resolve("original-compiler"))
        try {
            Files.createSymbolicLink(parent, replacement.fileName)
            f.installation.verify() // Existing legacy leaf/byte checks alone still see equal bytes.
            expectModelFailure("compiler_model_compiler_path_unsafe") { binding.verifyInstallation() }
            expectModelFailure("compiler_model_compiler_path_unsafe") {
                KotlinJvmModelBinding.verifyAndBind(model, f.compilationId, f.installation)
            }
        } finally {
            Files.deleteIfExists(parent)
            Files.move(saved, parent)
        }
    }
}

internal fun expectModelFailure(code: String, action: () -> Unit) {
    assertEquals(code, assertFailsWith<KotlinJvmModelException>(block = action).code)
}

private fun bridgeTestDigest(value: AnalysisContext): String = Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(value)))

internal fun withBridgeTestProfiles(input: AnalysisInput, profiles: Map<String, TrustedExecution>): AnalysisInput =
    input.copy(context = input.context.copy(policies = input.context.policies +
        ("ktchecker.execution-plan" to Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(profiles))))))

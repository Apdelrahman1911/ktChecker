package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.*
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Path
import kotlin.test.*

/** Pure declaration/classifier tests. No declared artifact here is compiler execution evidence.
 * In particular, a test declaration may name the required compiler digest, but is never imported
 * against fabricated bytes or used to construct a positive installed compiler binding. */
class JvmOriginalArgumentRolesTest {
    @Test fun everyOriginalTokenHasOneIndexedRoleAndReconstructsWithoutStripping(): Unit {
        val f = data(); val checked = f.classify()
        assertEquals(f.compilation.compilerArguments, checked.originalArguments)
        assertEquals(f.compilation.compilerArguments, checked.renderOriginal())
        assertEquals(checked.originalArguments.indices.toList(), checked.tokens.map { it.index })
        assertEquals(listOf("-jvm-target", "17", "-module-name", "pure_layout"), checked.normalizedCompilerOptions)
        assertEquals(listOf(0, 1), checked.tokens.filter { it.role == JvmArgumentTokenRole.SOURCE }.map { it.sourceOrdinal })
        assertEquals(1, checked.tokens.count { it.role == JvmArgumentTokenRole.CLASSPATH_FLAG })
        assertEquals(1, checked.tokens.count { it.role == JvmArgumentTokenRole.NONEMPTY_SOURCE_GATE })
        assertTrue(Digests.valid(checked.digest))
    }

    @Test fun originalSourceOrderIsDistinctFromModelMemberOrderAndBothAreBound(): Unit {
        val f = data(); val checked = f.classify()
        assertEquals(listOf("src/A.kt", "src/Z.kt"), checked.modelSourceFiles.map { it.path })
        assertEquals(listOf("src/Z.kt", "src/A.kt"), checked.executableCompilationFiles)
        assertEquals(f.compilation.sourceFiles.reversed(), checked.executableSourceFiles)
        val reversedMembers = f.classify(f.compilation.copy(sourceFiles = f.compilation.sourceFiles.reversed()))
        assertEquals(checked.executableSourceFiles, reversedMembers.executableSourceFiles)
        assertNotEquals(checked.digest, reversedMembers.digest, "Model member order may not silently become a set identity")
        val roles = requireNotNull(f.compilation.jvmArgumentRoles)
        unsupported { f.classify(f.compilation.copy(jvmArgumentRoles = roles.copy(sourcePaths = roles.sourcePaths.reversed()))) }
        val sourceReordered = f.compilation.copy(jvmArgumentRoles = roles.copy(sourcePaths = roles.sourcePaths.reversed()),
            compilerArguments = f.compilation.compilerArguments.dropLast(2) + f.compilation.compilerArguments.takeLast(2).reversed())
        assertNotEquals(checked.digest, f.classify(sourceReordered).digest)
    }

    @Test fun unknownAliasResponsePluginFriendAndFrontendFlagsNeverNormalizeAway(): Unit {
        val f = data()
        for (token in listOf("-cp", "-classpath=/elsewhere.jar", "-d=/elsewhere", "@args.txt", "--", "-unknown",
            "-Xplugin=/plugin.jar", "-P", "-Xcompiler-plugin=/plugin.jar", "-Xfriend-paths=/classes", "-J-Xmx1g",
            "-script", "-Xuse-fir-lt=false", "-Xmulti-platform", "-Xjava-source-roots=/java", "-no-jdk")) {
            val original = f.compilation.compilerArguments
            unsupported { f.classify(f.compilation.copy(compilerArguments = listOf(token) + original)) }
            assertEquals(original, f.compilation.compilerArguments)
        }
    }

    @Test fun duplicateOptionsAndPartialOrInterspersedSourcesAreRejected(): Unit {
        val f = data(); val original = f.compilation.compilerArguments
        for (changed in listOf(
            listOf("-no-stdlib") + original,
            listOf("-jvm-target", "21") + original,
            original.dropLast(1), original + original.last(),
            original.dropLast(2) + original.last() + original[original.lastIndex - 1],
            original.dropLast(1) + "-nowarn" + original.last(),
            original.dropLast(1) + "/owner/source/src/Other.kt",
        )) unsupported { f.classify(f.compilation.copy(compilerArguments = changed)) }
    }

    @Test fun sourceFreeFlagIsOnlyAdmittedWithTheCompleteNonemptyKotlinLeafDomain(): Unit {
        val f = data()
        val withoutGate = f.compilation.copy(compilerArguments = f.compilation.compilerArguments.filterNot { it == "-Xallow-no-source-files" })
        assertTrue(f.classify(withoutGate).tokens.none { it.role == JvmArgumentTokenRole.NONEMPTY_SOURCE_GATE })
        unsupported { f.classify(f.compilation.copy(sourceFiles = emptyList(),
            jvmArgumentRoles = f.compilation.jvmArgumentRoles!!.copy(sourcePaths = emptyList()),
            compilerArguments = f.compilation.compilerArguments.dropLast(2))) }
        unsupported { f.classify(f.compilation.copy(sourceFiles = listOf(f.compilation.sourceFiles.first().copy(path = "src/A.java")))) }
        unsupported { f.classify(f.compilation.copy(sourceFiles = f.compilation.sourceFiles + f.compilation.sourceFiles.first())) }
        unsupported { f.classify(f.compilation.copy(sourceFiles = f.compilation.sourceFiles.map { it.copy(size = -1) })) }
    }

    @Test fun absentAndExplicitSameSelectedJdkHaveDifferentOriginalRoles(): Unit {
        val f = data(); val roles = f.compilation.jvmArgumentRoles!!
        assertNull(roles.jdkHomeOperand)
        assertTrue(f.classify().tokens.none { it.role == JvmArgumentTokenRole.JDK_VALUE })
        val explicit = f.compilation.copy(jvmArgumentRoles = roles.copy(jdkHomeOperand = roles.selectedJdkHome),
            compilerArguments = listOf("-jdk-home", roles.selectedJdkHome) + f.compilation.compilerArguments)
        val checked = f.classify(explicit)
        assertEquals(roles.selectedJdkHome, checked.tokens.single { it.role == JvmArgumentTokenRole.JDK_VALUE }.original)
        assertNotEquals(f.classify().digest, checked.digest)
        unsupported { f.classify(explicit.copy(jvmArgumentRoles = roles)) }
        unsupported { f.classify(f.compilation.copy(jvmArgumentRoles = explicit.jvmArgumentRoles)) }
        unsupported { f.classify(explicit.copy(jvmArgumentRoles = roles.copy(jdkHomeOperand = "/different-jdk"))) }
    }

    @Test fun destinationJdkSourceAndRequestMustBelongToIndependentlyRegisteredOrigin(): Unit {
        val f = data(); val roles = f.compilation.jvmArgumentRoles!!
        for (changed in listOf(roles.copy(destinationPath = "/owner/elsewhere/classes"),
            roles.copy(selectedJdkHome = "/other-jdk"), roles.copy(compileTaskPath = ":otherTask"))) {
            unsupported { f.classify(f.compilation.copy(jvmArgumentRoles = changed)) }
        }
        unsupported { classifyJvmArgumentLayout(f.request.copy(configurationDigest = Digests.sha256("different")), f.compilation, f.origin, emptyList()) }
        unsupported { f.classify(f.compilation.copy(compileTaskPath = ":otherTask")) }
        unsupported { ModelJvmArgumentOrigin(f.request, ":||main", ":compileKotlin", ":", "main",
            Path.of("/owner/source/output"), Path.of("/owner/jdk")) }
        unsupported { ModelJvmArgumentOrigin(f.request, ":||main", ":compileKotlin", ":", "main",
            Path.of("/owner/output/../other"), Path.of("/owner/jdk")) }
    }

    @Test fun nullEmptyAndNonemptyClasspathAreNotSubstitutedForOneAnother(): Unit {
        val f = data(); val roles = f.compilation.jvmArgumentRoles!!
        assertNotEquals(ContractJson.encodeToString(roles.copy(classpathOperand = null)),
            ContractJson.encodeToString(roles.copy(classpathOperand = "")))
        for (operand in listOf(null, "")) unsupported {
            f.classify(f.compilation.copy(classpath = emptyList(), jvmArgumentRoles = roles.copy(classpathOperand = operand)))
        }
        unsupported { f.classify(f.compilation.copy(classpath = f.compilation.classpath.reversed())) }
        unsupported { f.classify(f.compilation.copy(jvmArgumentRoles = roles.copy(classpathOperand = null))) }
        unsupported { f.classify(f.compilation.copy(classpath = f.compilation.classpath.map { it.copy(digest = null) })) }
        unsupported { f.classify(f.compilation.copy(classpath = f.compilation.classpath.map { it.copy(path = "/owner/source/lib.jar") })) }
    }

    @Test fun classpathOrderAndPhysicalOccurrencesAreBoundWithoutDigestOrCoordinateDeduplication(): Unit {
        val f = data(); val original = f.compilation
        val cp = original.classpath.map { it.copy(coordinate = "same:coordinate:1", digest = Digests.sha256("same bytes"), size = 10) }
        val repeatedBytes = original.copy(classpath = cp)
        assertEquals(original.compilerArguments, f.classify(repeatedBytes).originalArguments)
        val reversed = cp.reversed()
        val operand = reversed.joinToString(":") { it.path }
        val newArgs = original.compilerArguments.toMutableList().also { it[it.indexOf("-classpath") + 1] = operand }
        val reordered = repeatedBytes.copy(classpath = reversed,
            jvmArgumentRoles = original.jvmArgumentRoles!!.copy(classpathOperand = operand), compilerArguments = newArgs)
        assertNotEquals(f.classify(repeatedBytes).digest, f.classify(reordered).digest)
        unsupported { f.classify(original.copy(classpath = listOf(cp.first(), cp.first()))) }
    }

    @Test fun onlyTheFiniteScalarWireVocabularyCanBeProjected(): Unit {
        val f = data()
        for (language in listOf("2.0", "2.1", "2.2", "2.3", "2.4")) {
            val original = listOf("-language-version", language, "-api-version", language, "-nowarn", "-Werror", "-progressive") + f.compilation.compilerArguments
            val checked = f.classify(f.compilation.copy(compilerArguments = original, languageVersion = language, apiVersion = language))
            assertEquals(original.take(7) + listOf("-jvm-target", "17", "-module-name", "pure_layout"), checked.normalizedCompilerOptions)
        }
        for ((flag, bad) in listOf("-language-version" to "1.9", "-api-version" to "3.0", "-module-name" to "../escape")) {
            unsupported { f.classify(f.compilation.copy(compilerArguments = listOf(flag, bad) + f.compilation.compilerArguments)) }
        }
        for (target in listOf("1.8", "11", "17", "21")) {
            val argv = f.compilation.compilerArguments.toMutableList().also { it[it.indexOf("-jvm-target") + 1] = target }
            assertTrue(target in f.classify(f.compilation.copy(compilerArguments = argv)).normalizedCompilerOptions)
        }
        unsupported { f.classify(f.compilation.copy(languageVersion = "2.4")) }
    }

    @Test fun onlyExactCompilerPinsAndSupportedIndependentDomainsCanExposeNormalization(): Unit {
        val f = data(); val chain = f.compilation.toolchain!!
        for (compiler in listOf(emptyList(), chain.compilerArtifacts + chain.compilerArtifacts, chain.compilerArtifacts.map {
            it.copy(digest = Digests.sha256("different compiler"))
        })) unsupported { f.classify(f.compilation.copy(toolchain = chain.copy(compilerArtifacts = compiler))) }
        for (changed in listOf(
            f.compilation.copy(target = "named"), f.compilation.copy(platform = "common"),
            f.compilation.copy(toolchain = chain.copy(compilerVersion = "2.4.20")),
            f.compilation.copy(friendArtifacts = f.compilation.classpath),
            f.compilation.copy(auxiliaryInputFiles = f.compilation.sourceFiles),
            f.compilation.copy(associatedCompilationIds = listOf(":||other")),
            f.compilation.copy(toolchain = chain.copy(compilerPlugins = f.compilation.classpath)),
            f.compilation.copy(projectDependencies = listOf(ModelProjectDependency(":other", "api", emptyMap(), ":other||main"))),
            f.compilation.copy(jvmArgumentRoles = f.compilation.jvmArgumentRoles!!.copy(pluginDataState = "getter_failed")),
        )) unsupported { f.classify(changed) }
        val generated = GeneratedSourceInput(":generate", f.compilation.sourceSetIds, emptyList(), f.compilation.sourceFiles, listOf("src"), "captured")
        unsupported { classifyJvmArgumentLayout(f.request, f.compilation, f.origin, listOf(generated)) }
    }

    @Test fun layoutDetachesMutableVectorsAndItsViewsCannotBeMutated(): Unit {
        val f = data(); val args = f.compilation.compilerArguments.toMutableList(); val members = f.compilation.sourceFiles.toMutableList()
        val paths = f.compilation.jvmArgumentRoles!!.sourcePaths.toMutableList()
        val checked = f.classify(f.compilation.copy(compilerArguments = args, sourceFiles = members,
            jvmArgumentRoles = f.compilation.jvmArgumentRoles!!.copy(sourcePaths = paths)))
        args.clear(); members.clear(); paths.clear()
        assertEquals(f.compilation.compilerArguments, checked.renderOriginal())
        assertEquals(f.compilation.sourceFiles, checked.modelSourceFiles)
        for (view in listOf(checked.originalArguments, checked.tokens, checked.normalizedCompilerOptions, checked.modelSourceFiles,
            checked.executableSourceFiles, checked.executableCompilationFiles, checked.renderOriginal())) {
            assertFailsWith<UnsupportedOperationException> { (view as MutableList<*>).clear() }
        }
    }

    @Test fun malformedAndOversizedOriginalVectorsNeverFallBackToPartialOptions(): Unit {
        val f = data()
        for (bad in listOf(emptyList(), List(129) { "-nowarn" }, listOf(""), listOf("x".repeat(8193)), listOf("bad\nargument"))) {
            unsupported { f.classify(f.compilation.copy(compilerArguments = bad)) }
        }
        unsupported { f.classify(f.compilation.copy(jvmArgumentRoles = null)) }
    }

    private fun unsupported(operation: () -> Unit) {
        assertEquals("model_jvm_arguments_unsupported", assertFailsWith<ProjectModelException>(block = operation).code)
    }

    private data class Data(val request: ModelImportRequest, val compilation: ModelCompilation, val origin: ModelJvmArgumentOrigin) {
        fun classify(selected: ModelCompilation = compilation): VerifiedJvmArgumentLayout = classifyJvmArgumentLayout(request, selected, origin, emptyList())
    }

    private fun data(): Data {
        assumeTrue(File.separator == "/", "The reviewed role adapter is explicitly POSIX-only")
        val files = listOf(ModelFile("src/A.kt", Digests.sha256("fun a() = 1\n"), 12), ModelFile("src/Z.kt", Digests.sha256("fun z() = 2\n"), 12))
        val namespace = ModelNamespace("declaration-owner", "declaration-workspace", "declaration-worktree", "declaration-lineage")
        val snapshot = SnapshotManifest(Digests.sha256("declaration-only snapshot"), namespace.workspaceId, namespace.worktreeId,
            files.map { SnapshotFile(it.path, it.digest, it.size) }, namespace.ownerId)
        val request = ModelImportRequest(namespace, snapshot, "/owner/source", Digests.sha256("declaration configuration"),
            Digests.sha256("declaration export"), Digests.sha256("declaration exporter"), "declaration-only-not-an-execution")
        val origin = ModelJvmArgumentOrigin(request, ":||main", ":compileKotlin", ":", "main", Path.of("/owner/output"), Path.of("/owner/jdk"))
        val cp = listOf(ModelArtifact("data:a:1", "/owner/deps/a.jar", Digests.sha256("a"), 1),
            ModelArtifact("data:z:1", "/owner/deps/z.jar", Digests.sha256("z"), 1))
        val roles = ModelJvmArgumentRoles(compileTaskPath = ":compileKotlin", sourcePaths = files.reversed().map { it.path },
            classpathOperand = cp.joinToString(":") { it.path }, destinationPath = origin.destinationPath.toString(),
            selectedJdkHome = origin.selectedJdkHome.toString(), jdkHomeOperand = null, pluginDataState = "provider_absent")
        val args = listOf("-Xallow-no-source-files", "-classpath", roles.classpathOperand!!, "-d", roles.destinationPath,
            "-jvm-target", "17", "-module-name", "pure_layout", "-no-reflect", "-no-stdlib") +
            roles.sourcePaths.map { "/owner/source/$it" }
        val chain = ModelToolchain("jvm", "2.4.21", listOf(ModelArtifact("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21",
            "/owner/compiler/kotlin-compiler-embeddable.jar", VerifiedJvmArgumentLayout.COMPILER_DIGEST, 58_593_291)), emptyList())
        val compilation = ModelCompilation(":||main", ":", "", "jvm", "main", ":|main", listOf(":|main"), files,
            ":compileKotlin", "compileClasspath", cp, emptyList(), chain, args, null, null, jvmArgumentRoles = roles)
        return Data(request, compilation, origin)
    }
}

package dev.ktchecker.providers.native

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.ExactCompilationModel
import dev.ktchecker.providers.runtime.ExecutionProblem
import java.nio.file.Path
import kotlin.test.*

/** In-memory structural controls only: no source reads, process calls, OS spoof, or Native execution. */
class NativeCliSelectionTest {
    @Test fun `both exact ios aliases admit the original ordered argv without rewriting`() {
        for (target in listOf("iosArm64", "iosSimulatorArm64")) {
            val fixture = fixture(target)
            val arguments = fixture.input.execution!!.arguments
            val before = arguments.toList()
            requireNativeCliSelection(fixture.plan, fixture.input)
            assertSame(arguments, fixture.input.execution!!.arguments)
            assertEquals(before, arguments)
            assertEquals(fixture.input.analysisKey(), fixture.plan.cliSelection!!.expectedAnalysisKey)
        }
    }

    @Test fun `explicit source order and common selection never follow source set or directory names`() {
        val fixture = fixture()
        val selection = fixture.plan.cliSelection!!
        assertNotEquals(fixture.input.context.compilationFiles, selection.sourceFiles)
        assertEquals(fixture.input.context.compilationFiles!!.toSet(), selection.sourceFiles.toSet())
        assertTrue(commonOne.startsWith("src/iosMain/"))
        assertTrue(platform.startsWith("src/commonMain/"))
        assertFalse(platform in selection.commonSourceFiles)
        requireNativeCliSelection(fixture.plan, fixture.input)
        // The owner may explicitly model an all-common compilation, including missing-actual cases.
        val allCommon = fixture.withArguments(arguments(common = selection.sourceFiles))
        requireNativeCliSelection(allCommon.plan.copy(cliSelection = allCommon.plan.cliSelection!!.copy(
            commonSourceFiles = selection.sourceFiles)), allCommon.input)
    }

    @Test fun `missing owner selection is unavailable and a valid dto never grants execution authority`() {
        val fixture = fixture()
        fails("native_cli_selection_unavailable", fixture.copy(plan = fixture.plan.copy(cliSelection = null)))
        assertEquals("native_trusted_plan_unavailable", assertFailsWith<ExecutionProblem> {
            KotlinNativeProvider().requireCliSelection(fixture.input)
        }.code)
        assertFalse(fixture.input.execution!!.approved)
        // This pure gate can pass with the provider's default DENY authority: it is not a launch.
        KotlinNativeProvider(plan = fixture.plan).requireCliSelection(fixture.input)
    }

    @Test fun `exact analysis key binds argv order captured identities model scope and context`() {
        val fixture = fixture()
        val input = fixture.input
        val changed = listOf(
            input.copy(execution = input.execution!!.copy(arguments = input.execution!!.arguments.reversed())),
            input.copy(snapshot = input.snapshot.copy(snapshotId = Digests.sha256("different snapshot"))),
            input.copy(snapshot = input.snapshot.copy(files = input.snapshot.files.map { it.copy(digest = Digests.sha256("different bytes")) })),
            input.copy(context = input.context.copy(modelDigest = Digests.sha256("different model"))),
            input.copy(context = input.context.copy(scope = input.context.scope.copy(module = ":other"))),
            input.copy(context = input.context.copy(compilationFiles = input.context.compilationFiles!!.reversed())),
            input.copy(context = input.context.copy(compilerArguments = listOf("different intended task options"))),
            input.copy(context = input.context.copy(configurationDigest = Digests.sha256("different policy"))),
            input.copy(context = input.context.copy(dependencyDigest = Digests.sha256("different dependencies"))),
            input.copy(context = input.context.copy(toolchainDigest = Digests.sha256("different toolchain"))),
        )
        changed.forEach { fails("native_cli_selection_binding_mismatch", fixture.copy(input = it)) }
        for (key in listOf("", "sha256:bad", Digests.sha256("another descriptor"))) {
            fails("native_cli_selection_binding_mismatch", fixture.copy(plan = fixture.plan.copy(
                cliSelection = fixture.plan.cliSelection!!.copy(expectedAnalysisKey = key))))
        }
    }

    @Test fun `the grammar is pinned to the exact native version mode and target`() {
        val fixture = fixture()
        fails("native_compiler_version_unavailable", fixture.copy(plan = fixture.plan.copy(kotlinVersion = "2.4.20")))
        fails("native_compiler_version_unavailable", fixture.withInput(fixture.input.copy(
            context = fixture.input.context.copy(compilerVersion = null))))
        fails("native_ios_target_model_required", fixture.copy(plan = fixture.plan.copy(target = "jvm")))
        fails("native_ios_target_model_required", fixture.withInput(fixture.input.copy(
            context = fixture.input.context.copy(scope = fixture.input.context.scope.copy(target = "iosSimulatorArm64")))))
        fails("native_execution_mode_unsupported", fixture.copy(plan = fixture.plan.copy(mode = "gradle")))
        fails("native_cli_arguments_unavailable", fixture.withInput(fixture.input.copy(execution = null)))
    }

    @Test fun `missing duplicate conflicting and aliased target options are refused`() {
        val fixture = fixture()
        val args = fixture.input.execution!!.arguments
        fails("native_cli_required_option_missing", fixture.withArguments(args.drop(2)))
        fails("native_cli_target_binding_invalid", fixture.withArguments(args.toMutableList().also { it[1] = "ios_simulator_arm64" }))
        for (target in listOf("ios_arm64", "ios_simulator_arm64", "linux_x64")) {
            fails("native_cli_option_duplicate", fixture.withArguments(args + listOf("-target", target)))
        }
        for (option in listOf("-target=ios_arm64", "--target=ios_arm64", "-t")) {
            fails("native_cli_argument_form_unsupported", fixture.withArguments(args + option))
        }
    }

    @Test fun `required scalar options are unique complete and never boolean assignment aliases`() {
        val fixture = fixture()
        val args = fixture.input.execution!!.arguments
        for (option in listOf("-Xmulti-platform", "-Xrender-internal-diagnostic-names")) {
            fails("native_cli_required_option_missing", fixture.withArguments(args - option))
            fails("native_cli_option_duplicate", fixture.withArguments(args + option))
            for (value in listOf("true", "false")) {
                fails("native_cli_argument_form_unsupported", fixture.withArguments(args + "$option=$value"))
            }
        }
        for (option in listOf("-target", "-produce", "-o")) {
            val index = args.indexOf(option)
            val without = args.take(index) + args.drop(index + 2)
            fails("native_cli_required_option_missing", fixture.withArguments(without))
            fails("native_cli_argument_form_unsupported", fixture.withArguments(without + option))
            fails("native_cli_option_duplicate", fixture.withArguments(args + args.subList(index, index + 2)))
        }
        fails("native_cli_required_option_missing", fixture.withArguments(args.filterNot { it.startsWith("-Xcommon-sources=") }))
        fails("native_cli_option_duplicate", fixture.withArguments(args + args.single { it.startsWith("-Xcommon-sources=") }))
    }

    @Test fun `empty duplicated reordered and mismatched common operands are refused`() {
        val fixture = fixture()
        val values = listOf("", "{snapshot}/$commonOne,", ",{snapshot}/$commonOne",
            "{snapshot}/$commonOne,{snapshot}/$commonOne", "{snapshot}/$commonTwo,{snapshot}/$commonOne",
            "{snapshot}/$platform", "{snapshot}/$sibling", "$commonOne,$commonTwo", "{snapshot}/./$commonOne")
        for (value in values) {
            fails("native_cli_common_sources_mismatch", fixture.withArguments(fixture.input.execution!!.arguments.map {
                if (it.startsWith("-Xcommon-sources=")) "-Xcommon-sources=$value" else it
            }))
        }
    }

    @Test fun `the explicit owner common list must be nonempty unique and within the exact members`() {
        val fixture = fixture()
        for (common in listOf(emptyList(), listOf(commonOne, commonOne), listOf(sibling), listOf("uncaptured.kt"))) {
            fails("native_cli_common_sources_invalid", fixture.copy(plan = fixture.plan.copy(
                cliSelection = fixture.plan.cliSelection!!.copy(commonSourceFiles = common))))
        }
    }

    @Test fun `owner source selection cannot replace missing duplicate or different imported members`() {
        val fixture = fixture()
        for (sources in listOf(emptyList(), orderedSources + commonOne)) {
            fails("native_cli_source_selection_invalid", fixture.copy(plan = fixture.plan.copy(
                cliSelection = fixture.plan.cliSelection!!.copy(sourceFiles = sources))))
        }
        fails("native_cli_source_selection_mismatch", fixture.copy(plan = fixture.plan.copy(
            cliSelection = fixture.plan.cliSelection!!.copy(sourceFiles = orderedSources + sibling))))
        fails("compilation_model_unavailable", fixture.copy(plan = fixture.plan.copy(compilationModel = null)))
        fails("compilation_members_unavailable", fixture.withInput(fixture.input.copy(context = fixture.input.context.copy(compilationFiles = null))))
        fails("compilation_members_duplicate", fixture.withInput(fixture.input.copy(context = fixture.input.context.copy(
            compilationFiles = fixture.input.context.compilationFiles!! + commonOne))))
        val model = fixture.plan.compilationModel!!
        fails("compilation_model_binding_mismatch", fixture.copy(plan = fixture.plan.copy(compilationModel = model.copy(modelDigest = Digests.sha256("other")))))
        fails("compilation_model_binding_mismatch", fixture.copy(plan = fixture.plan.copy(compilationModel = model.copy(scope = model.scope.copy(compilation = "other")))))
        fails("compilation_source_ownership_unavailable", fixture.copy(plan = fixture.plan.copy(compilationModel = model.copy(
            sourceSetByFile = model.sourceSetByFile - commonOne))))
    }

    @Test fun `extra missing duplicated and reordered positional compilation sources are refused`() {
        val fixture = fixture()
        val args = fixture.input.execution!!.arguments
        val noSources = args.filterNot { it.startsWith("{snapshot}/") }
        for (sources in listOf(orderedSources + sibling, orderedSources.dropLast(1), orderedSources + commonOne,
            orderedSources.reversed(), emptyList())) {
            fails("native_cli_source_selection_mismatch", fixture.withArguments(noSources + sources.map { "{snapshot}/$it" }))
        }
    }

    @Test fun `captured members must be present unique and carry structurally valid byte identities`() {
        val fixture = fixture()
        val manifest = fixture.input.snapshot
        fails("compilation_member_not_in_manifest", fixture.withInput(fixture.input.copy(snapshot = manifest.copy(
            files = manifest.files.filterNot { it.path == commonOne }))))
        for (files in listOf(manifest.files + manifest.files.first(), manifest.files + manifest.files.last(),
            manifest.files.map { if (it.path == commonOne) it.copy(digest = "unbound") else it },
            manifest.files.map { if (it.path == commonOne) it.copy(size = -1) else it })) {
            fails("native_cli_manifest_invalid", fixture.withInput(fixture.input.copy(snapshot = manifest.copy(files = files))))
        }
        fails("native_cli_manifest_invalid", fixture.withInput(fixture.input.copy(snapshot = manifest.copy(snapshotId = "unbound"))))
    }

    @Test fun `source paths reject aliases directories scripts delimiters placeholders and controls`() {
        val fixture = fixture()
        for (path in listOf("../A.kt", "./A.kt", "/A.kt", "a//A.kt", "a/../A.kt", "C:/A.kt", "a\\A.kt",
            "a/A.kt,b/B.kt", "a/A\n.kt", "a/A\u007f.kt", "a/A\u0085.kt", "{output}/A.kt", "a/*.kt", "a/?.kt",
            "a/[A].kt", "a/\"A\".kt", "a/'A'.kt", "a", "a/script.kts")) {
            val selection = fixture.plan.cliSelection!!.copy(sourceFiles = listOf(path), commonSourceFiles = listOf(path))
            fails("native_cli_source_path_unsupported", fixture.copy(plan = fixture.plan.copy(cliSelection = selection)))
        }
        for (token in listOf("{snapshot}/../A.kt", "{snapshot}//A.kt", "{snapshot}/{output}/A.kt", "{snapshot}/directory")) {
            fails("native_cli_source_path_unsupported", fixture.withArguments(fixture.input.execution!!.arguments + token))
        }
    }

    @Test fun `snapshot expansion cannot introduce a comma list placeholder alias or control`() {
        val fixture = fixture()
        for (root in listOf("relative-root", absolute("comma,root"), absolute("{output}"), absolute("{snapshot}"),
            fixture.input.materializedRoot + "/line\nbreak", fixture.input.materializedRoot + "/wild*card", fixture.input.materializedRoot + "/../alias")) {
            fails("native_cli_snapshot_path_unsupported", fixture.withInput(fixture.input.copy(materializedRoot = root)))
        }
    }

    @Test fun `response shell fragment unknown and alternate argument forms are unavailable`() {
        val fixture = fixture()
        for (argument in listOf("@args.txt", "@{snapshot}/args.txt", "--", "--help", "-version", "-Xunknown=ignored",
            "-Xfragments=common,ios", "-Xfragment-sources=common:{snapshot}/$commonOne", "-Xfragment-refines=ios:common",
            "-Xcommon-sources", "-Xcommon-sources:{snapshot}/$commonOne", "-Xdisable-phases=ALL", "-Xplugin=plugin.jar",
            "-l", "-output", "-o={output}/library", "sh", "-c", commonOne, absolute(commonOne),
            "\"{snapshot}/$commonOne\"", "{output}/Extra.kt")) {
            fails("native_cli_argument_form_unsupported", fixture.withArguments(fixture.input.execution!!.arguments + argument))
        }
        fails("native_cli_argument_form_unsupported", fixture.withArguments(fixture.input.execution!!.arguments.map {
            if (it == "library") "framework" else it
        }))
    }

    @Test fun `an output must be exactly a safe relative operand under the isolated output token`() {
        val fixture = fixture()
        val args = fixture.input.execution!!.arguments
        val index = args.indexOf("-o") + 1
        for (output in listOf("{output}", "{output}/", "{output}/../escape", "{output}/./alias", "{output}//alias",
            "{output}/a\\b", "{output}/a:b", "{output}/a,b", "{output}/{snapshot}/overwrite", "{snapshot}/overwrite",
            "{output}suffix/library", "relative/library", absolute("library"), "{output}/wild*card")) {
            fails("native_cli_output_binding_invalid", fixture.withArguments(args.toMutableList().also { it[index] = output }))
        }
    }

    @Test fun `library operands preserve each explicit classpath identity and its original order`() {
        val libraries = listOf(artifact("first"), artifact("second"))
        val fixture = fixture(libraries = libraries)
        requireNativeCliSelection(fixture.plan, fixture.input)
        for (selected in listOf(libraries.dropLast(1), libraries.reversed(), libraries + artifact("unselected"))) {
            fails("native_cli_library_selection_mismatch", fixture.withArguments(arguments(libraries = selected)))
        }
        fails("native_cli_option_duplicate", fixture.withArguments(fixture.input.execution!!.arguments + listOf("-library", libraries.first().path)))
        fails("native_cli_argument_form_unsupported", fixture.withArguments(fixture.input.execution!!.arguments + "-library"))
        for (selected in listOf(libraries + libraries.first(), listOf(libraries.first().copy(digest = "bad")),
            listOf(libraries.first().copy(coordinate = "")), listOf(libraries.first().copy(path = "relative.klib")),
            listOf(libraries.first().copy(path = "{snapshot}/library.klib")), listOf(libraries.first().copy(path = absolute("bad,library.klib"))))) {
            fails("native_cli_library_identity_invalid", fixture.withInput(fixture.input.copy(context = fixture.input.context.copy(classpath = selected))))
        }
        val changedIdentity = fixture.input.copy(context = fixture.input.context.copy(classpath = listOf(libraries.first().copy(digest = Digests.sha256("changed"))) + libraries.drop(1)))
        fails("native_cli_selection_binding_mismatch", fixture.copy(input = changedIdentity))
    }

    @Test fun `empty control-bearing oversized and excessive arguments are unavailable`() {
        val fixture = fixture()
        for (extra in listOf("", "\u0000", "-Xmulti-platform\r", "-Xmulti-platform\n", "-Xmulti-platform\u0085", "x".repeat(8193))) {
            fails("native_cli_argument_form_unsupported", fixture.withArguments(fixture.input.execution!!.arguments + extra))
        }
        fails("native_cli_argument_form_unsupported", fixture.withArguments(emptyList()))
        fails("native_cli_argument_form_unsupported", fixture.withArguments(List(16_385) { "-Xmulti-platform" }))
    }

    @Test fun `provider constructor defensively copies owner selection lists and source ownership`() {
        val fixture = fixture()
        val sources = fixture.plan.cliSelection!!.sourceFiles.toMutableList()
        val common = fixture.plan.cliSelection!!.commonSourceFiles.toMutableList()
        val ownership = fixture.plan.compilationModel!!.sourceSetByFile.toMutableMap()
        val mutablePlan = fixture.plan.copy(cliSelection = fixture.plan.cliSelection!!.copy(sourceFiles = sources, commonSourceFiles = common),
            compilationModel = fixture.plan.compilationModel!!.copy(sourceSetByFile = ownership))
        val provider = KotlinNativeProvider(plan = mutablePlan)
        sources.clear()
        common.clear()
        ownership.clear()
        fails("native_cli_source_selection_invalid", fixture.copy(plan = mutablePlan))
        provider.requireCliSelection(fixture.input)
        val changed = fixture.input.copy(execution = fixture.input.execution!!.copy(arguments = arguments(sources = orderedSources.reversed())))
        assertEquals("native_cli_selection_binding_mismatch", assertFailsWith<ExecutionProblem> { provider.requireCliSelection(changed) }.code)
    }

    @Test fun `frozen selection lists do not retain mutable aliases and reject mutation`() {
        val sources = orderedSources.toMutableList()
        val common = listOf(commonOne, commonTwo).toMutableList()
        val frozen = NativeCliSelection(Digests.sha256("synthetic binding"), sources, common).frozenCopy()
        sources.reverse()
        common.clear()
        assertEquals(orderedSources, frozen.sourceFiles)
        assertEquals(listOf(commonOne, commonTwo), frozen.commonSourceFiles)
        assertFailsWith<UnsupportedOperationException> { (frozen.sourceFiles as MutableList<String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (frozen.commonSourceFiles as MutableList<String>).add(platform) }
    }

    @Test fun `existing gradle task selection remains independent of the optional cli binding`() {
        for ((target, task) in listOf("iosArm64" to "compileKotlinIosArm64", "iosSimulatorArm64" to "compileKotlinIosSimulatorArm64")) {
            val required = setOf(":selected:compileCommonMainKotlinMetadata", ":selected:$task")
            val plan = NativeRunPlan(target, emptyList(), requiredTasks = required)
            assertEquals("gradle", plan.mode)
            assertNull(plan.cliSelection)
            requireNativeTaskSelection(plan, required.toList(), ":selected")
        }
    }

    private fun fails(code: String, fixture: Fixture) {
        val error = assertFailsWith<ExecutionProblem> { requireNativeCliSelection(fixture.plan, fixture.input) }
        assertEquals(code, error.code)
    }

    private data class Fixture(val input: AnalysisInput, val plan: NativeRunPlan) {
        fun withInput(changed: AnalysisInput): Fixture = copy(input = changed, plan = plan.copy(
            cliSelection = plan.cliSelection!!.copy(expectedAnalysisKey = changed.analysisKey())))
        fun withArguments(arguments: List<String>): Fixture = withInput(input.copy(execution = input.execution!!.copy(arguments = arguments)))
    }

    private fun fixture(target: String = "iosArm64", libraries: List<ArtifactIdentity> = emptyList()): Fixture {
        val scope = RuleContext(target, "explicitLeaf", ":selected", "main")
        val modelDigest = Digests.sha256("synthetic imported model, no execution")
        val ownership = mapOf(commonOne to "sharedButNotCommonMain", commonTwo to "anotherRefinedOwner", platform to "explicitLeaf")
        val input = AnalysisInput(SnapshotManifest(Digests.sha256("synthetic captured manifest, no execution"), "unit", "native-cli",
            (orderedSources + sibling).map { SnapshotFile(it, Digests.sha256("synthetic bytes for $it"), 1) }), absolute("unused-native-source-root"),
            AnalysisContext(scope, compilerVersion = "2.4.21", classpath = libraries,
                compilerArguments = listOf("intended task options are not a CLI provenance substitute"),
                modelDigest = modelDigest, dependencyDigest = Digests.sha256("synthetic dependencies"),
                toolchainDigest = Digests.sha256("synthetic toolchain"), configurationDigest = Digests.sha256("synthetic configuration"),
                fullProject = true, compilationFiles = listOf(commonOne, platform, commonTwo)),
            execution = TrustedExecution(absolute("never-executed-konanc"), arguments(target, libraries = libraries), approved = false),
            outputRoot = absolute("unused-native-output-root"))
        return Fixture(input, NativeRunPlan(target, emptyList(), mode = "native_cli",
            compilationModel = ExactCompilationModel(scope, modelDigest, ownership),
            cliSelection = NativeCliSelection(input.analysisKey(), orderedSources, listOf(commonOne, commonTwo))))
    }

    private fun arguments(target: String = "iosArm64", sources: List<String> = orderedSources,
                          common: List<String> = listOf(commonOne, commonTwo), libraries: List<ArtifactIdentity> = emptyList()): List<String> =
        listOf("-target", if (target == "iosArm64") "ios_arm64" else "ios_simulator_arm64", "-Xmulti-platform",
            "-Xrender-internal-diagnostic-names", "-Xcommon-sources=" + common.joinToString(",") { "{snapshot}/$it" },
            "-produce", "library", "-o", "{output}/native-library") + libraries.flatMap { listOf("-library", it.path) } +
            sources.map { "{snapshot}/$it" }

    private fun artifact(name: String): ArtifactIdentity = ArtifactIdentity("synthetic:$name", Digests.sha256(name), absolute("$name.klib"))
    private fun absolute(name: String): String = Path.of("").toAbsolutePath().root.resolve("ktchecker-native-cli-pure").resolve(name).normalize().toString()

    private val commonOne = "src/iosMain/Shared.kt"
    private val commonTwo = "arbitrary/共同.kt"
    private val platform = "src/commonMain/Platform.kt"
    private val sibling = "unselected/Sibling.kt"
    private val orderedSources = listOf(platform, commonTwo, commonOne)
}

package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.SnapshotFile
import dev.ktchecker.contracts.SnapshotManifest
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import kotlin.test.*

/** Data-only comparisons. These tests do not create a runtime registry, run START, import
 * a real model, load a Kotlin compiler/plugin, or invoke a process/native supervisor. */
class OwnerFixtureJvmExecutableInputsTest {
    @Test fun exactThirteenTokensRoundTripWithOrderedDistinctFileRoles(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val classified = assertIs<OwnerJvmPlanResult.Classified>(f.classify())
        val plan = classified.data
        assertEquals(f.lib.compilerArguments, OwnerFixtureJvmExecutableInputs.renderIntended(classified))
        assertEquals((0..12).toList(), plan.slots.map { it.index })
        assertEquals(f.lib.compilerArguments, plan.slots.map { it.original })
        assertEquals(24, plan.files.size)
        val roles = plan.files.groupBy { it.role }
        assertEquals(mapOf(OwnerJvmFileRole.SOURCE to 1, OwnerJvmFileRole.COMPILE_CLASSPATH to 2,
            OwnerJvmFileRole.COMPILER_RUNTIME_CLASSPATH to 14, OwnerJvmFileRole.SCRIPTING_CLASSPATH to 7), roles.mapValues { it.value.size })
        roles.values.forEach { assertEquals(it.indices.toList(), it.map { file -> file.ordinal }) }
        assertEquals(f.runtime.map { it.path }, roles.getValue(OwnerJvmFileRole.COMPILER_RUNTIME_CLASSPATH).map { it.path })
        assertEquals(f.classpath.map { it.path }, roles.getValue(OwnerJvmFileRole.COMPILE_CLASSPATH).map { it.path })
        assertEquals(f.plugins.map { it.path }, roles.getValue(OwnerJvmFileRole.SCRIPTING_CLASSPATH).map { it.path })
        assertEquals(listOf("kotlin-build-tools-compat-2.4.21.jar", "kotlin-build-tools-impl-2.4.21.jar",
            "kotlin-tooling-core-2.4.21.jar", "kotlin-build-tools-cri-impl-2.4.21.jar", "kotlin-compiler-runner-2.4.21.jar",
            "kotlin-compiler-embeddable-2.4.21.jar", "kotlin-build-tools-api-2.4.21.jar", "kotlin-daemon-client-2.4.21.jar",
            "kotlin-stdlib-2.4.21.jar", "annotations-13.0.jar", "kotlin-script-runtime-2.4.21.jar", "kotlin-reflect-1.6.10.jar",
            "kotlin-daemon-embeddable-2.4.21.jar", "kotlinx-coroutines-core-jvm-1.8.0.jar"), f.runtime.map { Path.of(it.path).fileName.toString() })
        for (basename in listOf("kotlin-stdlib-2.4.21.jar", "annotations-13.0.jar")) {
            val repeated = plan.files.filter { Path.of(it.path).fileName.toString() == basename }
            assertEquals(3, repeated.size)
            assertEquals(3, repeated.map { it.role }.distinct().size)
            assertEquals(1, repeated.map { it.path to (it.digest to it.size) }.distinct().size)
        }
        assertEquals(2, plan.files.count { Path.of(it.path).fileName.toString() == "kotlin-script-runtime-2.4.21.jar" })
        assertIs<OwnerJvmArgSlot.Scalar>(plan.slots[8]).also { assertEquals("owner-model-fixture:lib", it.original) }
        assertIs<OwnerJvmArgSlot.PathList>(plan.slots[2]).also { assertEquals(":", it.separator); assertEquals("", it.prefix) }
        assertIs<OwnerJvmArgSlot.PathList>(plan.slots[11]).also { assertEquals(",", it.separator); assertEquals("-Xplugin=", it.prefix) }
        assertNull(plan.languageVersion); assertNull(plan.apiVersion)
        assertTrue(plan.versionMeaning.contains("defaults"))
        assertEquals(58L, roles.getValue(OwnerJvmFileRole.SOURCE).single().size)
        assertTrue(f.snapshot.files.any { it.path == "lib/src/main/resources/library.txt" })
        assertTrue(plan.files.none { it.path.endsWith("library.txt") })
        assertIs<OwnerJvmArgSlot.OutputTree>(plan.slots[4])
        assertIs<OwnerJvmTreePresence.Unobserved>(plan.output.presence)
        assertNull(plan.output.compilerAttemptId)
        assertEquals("kotlin-classes", plan.output.spec.purpose)
        assertTrue(plan.files.none { it.path == plan.output.intendedPath })
        assertEquals(OwnerJvmComparisonOrigin.SYNTHETIC_DATA_ONLY, plan.provenance.comparisonOrigin)
        assertEquals(15, plan.scripting.missingFacts.size)
        assertTrue(Digests.valid(plan.rawArgumentsDigest)); assertTrue(Digests.valid(plan.inputCoreIdentity))
        assertNotEquals(plan.rawArgumentsDigest, plan.inputCoreIdentity)
        assertFailsWith<UnsupportedOperationException> { (plan.files as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (plan.slots as MutableList<*>).clear() }
    }

    @Test fun everyTokenRemovalInsertionDuplicationReorderAndValueChangeIsRejected(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val argv = f.lib.compilerArguments
        for (index in argv.indices) {
            for (changed in listOf(argv.filterIndexed { i, _ -> i != index },
                argv.toMutableList().apply { add(index, "-unmodeled") },
                argv.toMutableList().apply { add(index, argv[index]) })) {
                assertEquals("owner_jvm_argument_count_changed", f.rejectArguments(changed).code, "index=$index")
            }
            for (replacement in listOf("", argv[index] + " ")) {
                val rejection = f.rejectArguments(argv.mapIndexed { i, value -> if (i == index) replacement else value })
                assertEquals(index, rejection.tokenIndex)
                assertEquals(when (index) {
                    2, 11 -> "owner_jvm_path_operand_changed"
                    4 -> "owner_jvm_output_operand_changed"
                    12 -> "owner_jvm_source_operand_changed"
                    else -> "owner_jvm_scalar_changed"
                }, rejection.code)
            }
            if (index < argv.lastIndex) {
                val reordered = argv.toMutableList().apply { this[index] = argv[index + 1]; this[index + 1] = argv[index] }
                assertNotNull(f.rejectArguments(reordered).tokenIndex)
            }
        }
        for (extra in listOf("-P", "-P=plugin:kotlin.scripting:disable=true", "@options.txt", "Other.kt", "-nowarn")) {
            assertEquals("owner_jvm_argument_count_changed", f.rejectArguments(argv + extra).code)
        }
        for (plugin in listOf("-Xplugin", argv[11].replace("-Xplugin=", "-Xcompiler-plugin="),
            "-Xplugin=" + f.plugins.reversed().joinToString(",") { it.path }, "@plugin-arguments.txt")) {
            assertEquals("owner_jvm_path_operand_changed", f.rejectArguments(argv.toMutableList().apply { this[11] = plugin }).code)
        }
        for (changed in listOf(argv.toMutableList().apply { this[8] = "x".repeat(32_769) },
            argv.toMutableList().apply { this[8] = "module\u0000name" }, List(13) { "x".repeat(6_000) })) {
            assertEquals("owner_jvm_argument_bound", f.rejectArguments(changed).code)
        }
    }

    @Test fun classpathRolesRejectEveryPinShapeAndCoherentReportSelectedReplacement(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        for ((role, expected) in listOf("compile-classpath" to f.classpath, "compiler-runtime-classpath" to f.runtime,
            "scripting-classpath" to f.plugins)) {
            val first = expected.first()
            val changedFiles = listOf(first.copy(coordinate = "unreviewed:coordinate"),
                first.copy(path = Path.of(first.path).resolveSibling("0.jar").toString()),
                first.copy(path = first.path.replace("2.4.21", "2.4.20")),
                first.copy(path = f.bindings.ownerExporterOutput.resolve("generated.jar").toString()),
                first.copy(digest = Digests.sha256("substituted bytes")), first.copy(size = first.size!! + 1),
                first.copy(digest = null), first.copy(size = null))
            val variants = listOf(emptyList(), expected.drop(1), expected + first, expected.reversed(),
                listOf(first, first) + expected.drop(2)) + changedFiles.map { listOf(it) + expected.drop(1) }
            for (changed in variants) {
                val graph = f.changeLib { lib ->
                    // Even changing the matching raw operand cannot self-enroll new bytes/order.
                    val argv = lib.compilerArguments.toMutableList()
                    when (role) {
                        "compile-classpath" -> { argv[2] = changed.joinToString(":") { it.path }; lib.copy(classpath = changed, compilerArguments = argv) }
                        "compiler-runtime-classpath" -> lib.copy(toolchain = lib.toolchain!!.copy(compilerArtifacts = changed))
                        else -> { argv[11] = "-Xplugin=" + changed.joinToString(",") { it.path }
                            lib.copy(toolchain = lib.toolchain!!.copy(compilerPlugins = changed), compilerArguments = argv) }
                    }
                }
                val rejection = assertIs<OwnerJvmPlanResult.Rejected>(f.classify(graph)).problem
                assertEquals(role, rejection.role)
                assertEquals(when (role) {
                    "compile-classpath" -> "owner_jvm_compile_classpath_changed"
                    "compiler-runtime-classpath" -> "owner_jvm_compiler_runtime_classpath_changed"
                    else -> "owner_jvm_scripting_classpath_changed"
                }, rejection.code)
            }
        }
        val chain = f.lib.toolchain!!
        for (changed in listOf(null, chain.copy(kind = "metadata"), chain.copy(compilerVersion = null), chain.copy(compilerVersion = "2.4.20"))) {
            val rejection = assertIs<OwnerJvmPlanResult.Rejected>(f.classify(f.changeLib { it.copy(toolchain = changed) })).problem
            assertEquals(if (changed == null) "owner_jvm_toolchain_missing" else "owner_jvm_toolchain_changed", rejection.code)
        }
    }

    @Test fun fixedSourceContextVersionsAndEmptyAuxiliaryDomainsCannotBeWidened(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val source = f.lib.sourceFiles.single()
        val artifact = f.classpath.first()
        val mutations: List<Pair<String, (ModelCompilation) -> ModelCompilation>> = listOf(
            "owner_fixture_compilation_domain_incomplete" to { it.copy(id = ":lib|jvm|main") },
            "owner_fixture_actual_compilation_graph_changed" to { it.copy(compileTaskPath = ":lib:anotherTask") },
            "owner_fixture_actual_compile_members_incomplete" to { it.copy(sourceFiles = emptyList()) },
            "owner_fixture_actual_compile_members_incomplete" to { it.copy(sourceFiles = listOf(source, source)) },
            "owner_fixture_actual_compile_members_incomplete" to { it.copy(sourceFiles = listOf(source.copy(digest = Digests.sha256("changed source")))) },
            "owner_fixture_actual_compile_members_incomplete" to { it.copy(sourceFiles = listOf(source.copy(size = source.size + 1))) },
            "owner_fixture_actual_compilation_graph_changed" to { it.copy(projectDependencies = listOf(ModelProjectDependency(":consumer", "apiElements", emptyMap(), ":consumer||main"))) },
            "owner_fixture_actual_compilation_graph_changed" to { it.copy(associatedCompilationIds = listOf(":lib||test")) },
            "owner_jvm_compilation_domain_changed" to { it.copy(friendArtifacts = listOf(artifact)) },
            "owner_fixture_auxiliary_domain_unknown" to { it.copy(auxiliaryInputFiles = listOf(source)) },
            "owner_fixture_auxiliary_domain_unknown" to { it.copy(auxiliaryArtifacts = listOf(artifact)) },
            "owner_jvm_version_defaults_changed" to { it.copy(languageVersion = "2.4") },
            "owner_jvm_version_defaults_changed" to { it.copy(apiVersion = "2.4") },
            "owner_jvm_produced_variant_domain_changed" to { it.copy(producedVariants = listOf("runtimeElements")) },
        )
        mutations.forEach { (code, change) -> assertEquals(code, assertIs<OwnerJvmPlanResult.Rejected>(f.classify(f.changeLib(change))).problem.code) }
        assertEquals("owner_jvm_fixture_domain_changed", assertIs<OwnerJvmPlanResult.Rejected>(f.classify(f.graph.copy(compilations = f.graph.compilations.drop(1)))).problem.code)
        assertEquals("owner_fixture_generation_or_composite_domain_changed", assertIs<OwnerJvmPlanResult.Rejected>(f.classify(f.graph.copy(includedBuilds = listOf("unknown-build")))).problem.code)
        assertEquals("owner_fixture_capture_domain_incomplete", assertIs<OwnerJvmPlanResult.Rejected>(f.classify(snapshot = f.snapshot.copy(unknownInputs = listOf("unknown-reader")))).problem.code)
        assertEquals("owner_jvm_fixture_domain_changed", assertIs<OwnerJvmPlanResult.Rejected>(f.classify(snapshot = f.snapshot.copy(files = f.snapshot.files.drop(1)))).problem.code)
        assertEquals("owner_jvm_output_operand_changed", f.rejectArguments(f.lib.compilerArguments.toMutableList().apply { this[4] = f.bindings.ownerExporterOutput.resolve("lib.jar").toString() }).code)
        assertEquals("owner_jvm_source_operand_changed", f.rejectArguments(f.lib.compilerArguments.toMutableList().apply { this[12] = f.bindings.sourceRoot.resolve("lib/src/main/resources/library.txt").toString() }).code)
    }

    @Test fun selectedJdkTreeIdentityIncludesDirectoriesPermissionsAndCompleteDeclaredFileDomain(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val selected = f.bindings.jdk
        assertEquals(3, selected.identity.fileCount); assertEquals(2, selected.identity.directoryCount)
        assertEquals(f.bindings.jdk.identity.javaHome + "/bin/java", selected.identity.javaExecutable.path)
        val reordered = OwnerJvmJdkInputs.fromManifest(Path.of(selected.identity.javaHome), f.provenance.runtimeInputDigest,
            f.jdkFiles.reversed(), f.jdkDirectories.reversed())
        assertEquals(selected.identity, reordered.identity)
        val extraDirectory = OwnerJvmJdkInputs.fromManifest(Path.of(selected.identity.javaHome), f.provenance.runtimeInputDigest,
            f.jdkFiles, f.jdkDirectories + OwnerRuntimeDirectory("jdk", "empty"))
        assertNotEquals(selected.identity.treeDigest, extraDirectory.identity.treeDigest)
        val changedPermission = OwnerJvmJdkInputs.fromManifest(Path.of(selected.identity.javaHome), f.provenance.runtimeInputDigest,
            f.jdkFiles.map { if (it.relativePath == "lib/modules") it.copy(executable = true) else it }, f.jdkDirectories)
        assertNotEquals(selected.identity.treeDigest, changedPermission.identity.treeDigest)
        val baseline = assertIs<OwnerJvmPlanResult.Classified>(f.classify()).data.inputCoreIdentity
        assertNotEquals(baseline, assertIs<OwnerJvmPlanResult.Classified>(f.classify(bindings = f.bindings.copy(jdk = extraDirectory))).data.inputCoreIdentity)
        val files = f.lib.toolchain!!.runtimeArtifacts
        for (changed in listOf(files.drop(1), files + files.first(), files.map { it.copy(digest = null) },
            files.map { it.copy(coordinate = "jdk-by-target-only") }, files.map { it.copy(size = null) })) {
            assertEquals("owner_jvm_selected_jdk_changed", assertIs<OwnerJvmPlanResult.Rejected>(f.classify(f.changeLib {
                it.copy(toolchain = it.toolchain!!.copy(runtimeArtifacts = changed))
            })).problem.code)
        }
        fun reject(files: List<OwnerRuntimeFile>, directories: List<OwnerRuntimeDirectory> = f.jdkDirectories) =
            assertFailsWith<OwnerJvmPlanProblem> { OwnerJvmJdkInputs.fromManifest(Path.of(selected.identity.javaHome), f.provenance.runtimeInputDigest, files, directories) }.code
        assertEquals("owner_jvm_selected_java_missing", reject(f.jdkFiles.map { it.copy(executable = false) }))
        assertEquals("owner_jvm_jdk_parent_missing", reject(f.jdkFiles, emptyList()))
        assertEquals("owner_jvm_jdk_inventory_invalid", reject(f.jdkFiles + f.jdkFiles.first()))
        assertEquals("owner_jvm_jdk_inventory_invalid", reject(f.jdkFiles, f.jdkDirectories + f.jdkDirectories.first()))
        assertEquals("owner_jvm_jdk_inventory_invalid", reject(f.jdkFiles.map { it.copy(digest = "unidentified") }))
        assertEquals("owner_jvm_jdk_inventory_invalid", reject(f.jdkFiles.map { it.copy(kind = "seed") }))
        assertEquals("owner_jvm_jdk_bound", reject(emptyList()))
    }

    @Test fun roleRootsAndProvenanceAreBoundWithoutRelocatingTheRawArguments(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        for (bindings in listOf(f.bindings.copy(ownerExporterOutput = f.bindings.sourceRoot),
            f.bindings.copy(ownerExporterOutput = f.bindings.dependencyHome.resolve("generated")),
            f.bindings.copy(dependencyHome = f.bindings.sourceRoot.parent),
            f.bindings.copy(sourceRoot = Path.of(f.bindings.jdk.identity.javaHome).resolve("source")))) {
            assertEquals("owner_jvm_role_roots_overlap", assertIs<OwnerJvmPlanResult.Rejected>(f.classify(bindings = bindings)).problem.code)
        }
        for (path in listOf(Path.of("relative"), f.bindings.sourceRoot.resolve(".."), Path.of("/"), Path.of("/bad\\root"))) {
            assertEquals("owner_jvm_path_unsafe", assertIs<OwnerJvmPlanResult.Rejected>(f.classify(bindings = f.bindings.copy(sourceRoot = path))).problem.code)
        }
        for (separator in listOf(":", ",")) {
            assertEquals("owner_jvm_path_list_separator", assertIs<OwnerJvmPlanResult.Rejected>(f.classify(bindings = f.bindings.copy(
                dependencyHome = f.bindings.dependencyHome.resolveSibling("home${separator}unsafe")))).problem.code)
        }
        for (provenance in listOf(f.provenance.copy(snapshotId = Digests.sha256("wrong snapshot")),
            f.provenance.copy(sourceContractDigest = Digests.sha256("wrong source contract")),
            f.provenance.copy(runtimeInputDigest = Digests.sha256("wrong runtime")))) {
            assertIs<OwnerJvmPlanResult.Rejected>(f.classify(provenance = provenance))
        }
        assertEquals("owner_jvm_provenance_invalid", assertIs<OwnerJvmPlanResult.Rejected>(f.classify(provenance = f.provenance.copy(rawExportDigest = "not a digest"))).problem.code)
        val baseline = assertIs<OwnerJvmPlanResult.Classified>(f.classify()).data
        val otherStart = Digests.sha256("another synthetic START")
        val otherHome = Digests.sha256("another synthetic home")
        for (provenance in listOf(f.provenance.copy(rawExportDigest = Digests.sha256("another intended export")),
            f.provenance.copy(exporterExecutionId = "another-synthetic-exporter-id"),
            f.provenance.copy(startupDigest = otherStart, modelIdentity = f.provenance.modelIdentity.copy(gradleRuntimeDigest =
                ownerFixtureRuntimeIdentity(f.provenance.runtimeInputDigest, otherStart, f.provenance.executionHomeDigest))),
            f.provenance.copy(executionHomeDigest = otherHome, modelIdentity = f.provenance.modelIdentity.copy(gradleRuntimeDigest =
                ownerFixtureRuntimeIdentity(f.provenance.runtimeInputDigest, f.provenance.startupDigest, otherHome))))) {
            val changed = assertIs<OwnerJvmPlanResult.Classified>(f.classify(provenance = provenance)).data
            assertNotEquals(baseline.inputCoreIdentity, changed.inputCoreIdentity)
            assertEquals(baseline.rawArgumentsDigest, changed.rawArgumentsDigest)
        }
    }

    @Test fun newContractCannotSelfEnrollEditedBytesAndRequiresStartTimePin(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val bytes = Files.readAllBytes(f.directory.resolve(OwnerFixtureExecutableContract.FILE_NAME))
        assertEquals(OwnerFixtureExecutableContract.PIN, Digests.sha256(bytes))
        assertEquals(f.sourceContract.digest, f.contract.value.sourceContractDigest)
        val edited = Files.createTempDirectory("ktchecker-owner-executable-contract-").toRealPath()
        assumeOwnerFixtureAnchoredReads(edited)
        for (content in listOf(bytes + byteArrayOf(10), bytes.toString(Charsets.UTF_8).replace("2.4.21", "2.4.20").toByteArray(),
            bytes.toString(Charsets.UTF_8).trimEnd().dropLast(1).plus(",\"approved\":true}\n").toByteArray())) {
            Files.write(edited.resolve(OwnerFixtureExecutableContract.FILE_NAME), content)
            assertEquals("owner_jvm_contract_pin_changed", assertFailsWith<OwnerJvmPlanProblem> { OwnerFixtureExecutableContract.load(edited) }.code)
        }
        val start = f.syntheticStart()
        verifyOwnerJvmContractAtStart(f.contract, start)
        val pin = start.inputs.single()
        for (changed in listOf(start.copy(inputs = emptyList()), start.copy(inputs = listOf(pin, pin)),
            start.copy(inputs = listOf(pin.copy(role = "post-export-report"))),
            start.copy(inputs = listOf(pin.copy(digest = Digests.sha256("changed start contract")))),
            start.copy(executableContractCaptureFailure = "synthetic_missing_start_input"))) {
            assertEquals("owner_jvm_contract_not_bound_at_start", assertFailsWith<OwnerJvmPlanProblem> { verifyOwnerJvmContractAtStart(f.contract, changed) }.code)
        }
        assertFalse(start.sourceToBinaryAttested); assertFalse(start.loadedJvmMemoryAttested)
    }

    @Test fun classifierAndEvidenceKeepAllFourSyntheticImportedContextsUnavailable(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val model = f.syntheticModel()
        val before = f.contexts(model)
        assertEquals(listOf(":consumer||main", ":consumer||test", ":lib||main", ":lib||test"), before.map { it.compilationId })
        assertTrue(before.all { it.modelCompleteness == "unavailable" && !it.fullProjectModel })
        assertTrue(model.compilationIds.all { model.context(it, f.sourceContract.digest).certificateId == null })
        val originalGraph = model.export
        val originalIssues = model.issues.toList()
        val result = f.classify(model.export)
        assertIs<OwnerJvmPlanResult.Classified>(result)
        val evidence = OwnerFixtureJvmExecutableInputs.evidence(result, before, originalIssues.map { it.kind })
        assertEquals("unavailable", evidence.executionState); assertFalse(evidence.qualified)
        assertEquals(before, evidence.importedContexts); assertEquals(before, f.contexts(model))
        assertSame(originalGraph, model.export); assertEquals(originalIssues, model.issues)
        assertEquals(listOf("artifact_unavailable", "produced_variants_unavailable", "compiler_arguments_unmodeled"), evidence.importedIssueKinds)
        val rejected = OwnerFixtureJvmExecutableInputs.evidence(f.classify(f.changeLib { it.copy(compilerArguments = emptyList()) }), before, evidence.importedIssueKinds)
        assertNull(rejected.plan); assertNotNull(rejected.rejection); assertEquals(before, rejected.importedContexts)
        assertFalse(rejected.qualified)
        val decoded = ContractJson.decodeFromString<OwnerJvmPlanEvidence>(ownerJvmPlanEvidenceBytes(evidence).toString(Charsets.UTF_8))
        assertEquals(evidence, decoded) // DTO round trip only, not an importer/registry/execution capability.
        assertEquals(OwnerJvmComparisonOrigin.SYNTHETIC_DATA_ONLY, decoded.plan!!.provenance.comparisonOrigin)
    }

    @Test fun evidenceBoundsAndClaimDenialsApplyBeforeSerialization(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val evidence = OwnerFixtureJvmExecutableInputs.evidence(f.classify(), f.contexts(f.syntheticModel()), f.graph.issues.map { it.kind })
        val plan = evidence.plan!!
        assertTrue(ownerJvmPlanEvidenceBytes(evidence).size <= OWNER_JVM_PLAN_EVIDENCE_LIMIT)
        for (changed in listOf(evidence.copy(limitations = listOf("x".repeat(200_000))),
            evidence.copy(importedContexts = List(5) { evidence.importedContexts.first() }),
            evidence.copy(importedIssueKinds = List(257) { "gap" }),
            evidence.copy(importedContexts = listOf(evidence.importedContexts.first().copy(gaps = List(257) { "gap" }))),
            evidence.copy(plan = plan.copy(files = plan.files + plan.files.first())),
            evidence.copy(plan = plan.copy(slots = plan.slots + plan.slots.first())),
            evidence.copy(plan = plan.copy(scripting = plan.scripting.copy(requiredDefaults = List(17) { plan.scripting.requiredDefaults.first() }))),
            evidence.copy(plan = plan.copy(scripting = plan.scripting.copy(intendedPluginOptions = listOf("-P")))),
            evidence.copy(plan = plan.copy(versionMeaning = "x".repeat(200_000))))) {
            assertEquals("owner_jvm_plan_evidence_bound", assertFailsWith<OwnerJvmPlanProblem> { verifyOwnerJvmPlanEvidenceBound(changed) }.code)
        }
        for (changed in listOf(evidence.copy(qualified = true), evidence.copy(executionState = "success"), evidence.copy(evidenceKind = "execution-permit"))) {
            assertEquals("owner_jvm_plan_claim_forbidden", assertFailsWith<OwnerJvmPlanProblem> { ownerJvmPlanEvidenceBytes(changed) }.code)
        }
        for (output in listOf(plan.output.copy(compilerAttemptId = "invented-attempt"),
            plan.output.copy(presence = OwnerJvmTreePresence.EmptyDirectory("invented-anchor")))) {
            assertEquals("owner_jvm_plan_output_observation_forbidden", assertFailsWith<OwnerJvmPlanProblem> {
                ownerJvmPlanEvidenceBytes(evidence.copy(plan = plan.copy(output = output)))
            }.code)
        }
        assertEquals(OwnerJvmPlanRejection("owner_jvm_plan_unavailable", "classifier"), ownerJvmPlanFailure(IllegalStateException("synthetic private detail must not be serialized")))
    }

    @Test fun separateEvidencePublicationIsCreateNewReadOnlyAndReadBackBound(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val work = Files.createTempDirectory("ktchecker-owner-plan-publication-").toRealPath()
        assumeOwnerFixtureAnchoredReads(work)
        assumeTrue(Files.getFileStore(work).supportsFileAttributeView("posix"))
        val evidence = OwnerFixtureJvmExecutableInputs.evidence(f.classify(), f.contexts(f.syntheticModel()), f.graph.issues.map { it.kind })
        val path = work.resolve("plan.json")
        val published = publishOwnerJvmPlanEvidence(path, evidence)
        val bytes = readOwnerFixtureInput(path, OWNER_JVM_PLAN_EVIDENCE_LIMIT)
        assertEquals(evidence.state, published.state); assertEquals(path.toString(), published.path)
        assertEquals(Digests.sha256(bytes), published.digest)
        assertEquals(setOf(PosixFilePermission.OWNER_READ), Files.getPosixFilePermissions(path, NOFOLLOW_LINKS))
        assertFails { publishOwnerJvmPlanEvidence(path, evidence) }
        assertContentEquals(bytes, Files.readAllBytes(path))
        val link = work.resolve("plan-alias.json")
        createOwnerFixtureSymbolicLink(link, path)
        assertFails { publishOwnerJvmPlanEvidence(link, evidence) }
        assertContentEquals(bytes, Files.readAllBytes(path))
    }

    @Test fun authoredIntegrationRemainsAfterImportAndOutsideExecutionAndEnrollmentGates(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val repository = f.directory.parent.parent.parent
        val sourceRoot = repository.resolve("modules/project-model/src/test/kotlin/dev/ktchecker/projectmodel")
        val runner = Files.readString(sourceRoot.resolve("OwnerModelFixtureRunner.kt"))
        val startup = Files.readString(sourceRoot.resolve("OwnerFixtureStartup.kt"))
        fun before(first: String, second: String) { assertTrue(runner.indexOf(first) >= 0); assertTrue(runner.indexOf(second) > runner.indexOf(first)) }
        before("OwnerFixturePreparedJvmInputs.bind", "OwnerFixtureInvocation.execute")
        before("ProjectModelImporter(ExactModelIngressAuthority(ingress)).import", "preparedPlan.classifyAfterImport")
        assertTrue(runner.contains("planEvidence = try {")); assertTrue(runner.contains("OwnerJvmPlanPublication(\"publication_failed\""))
        assertTrue(runner.contains("verifyModelInputs = { graph -> verifyRuntime(); contract.verifyDomain(capture.snapshot, graph, registry) }"))
        for (name in listOf("OwnerFixtureJvmExecutableInputs.kt", "OwnerFixtureDefaultScripting2421.kt", "OwnerFixtureDerivedOutputTree.kt")) {
            assertTrue(startup.contains("/" + name + "\""))
            val helper = Files.readString(sourceRoot.resolve(name))
            assertFalse(Regex("(?:IsolatedProcessRunner|ExecutionPermit|ProcessBuilder|KotlinJvmModelBinding|CompilerFlags|generatedProfile)\\s*\\(").containsMatchIn(helper))
            assertFalse(helper.contains("Class.forName")); assertFalse(helper.contains("URLClassLoader"))
        }
        assertTrue(startup.contains("\"authored-executable-input-contract\""))
        // These are source-wiring checks, not a claim that this driver or START actually ran.
    }
}

/** Shared synthetic metadata fixture for the three new test files. No JAR or JDK bytes
 * are loaded; even the 'VerifiedProjectModel' below is explicitly test-constructed data. */
internal class OwnerJvmSyntheticFixture private constructor(val directory: Path, val sourceContract: OwnerFixtureContract,
    val contract: OwnerFixtureExecutableContract, val snapshot: SnapshotManifest, val graph: GradleProjectExport,
    val bindings: OwnerJvmClassifierBindings, val provenance: OwnerJvmPlanProvenance,
    val jdkFiles: List<OwnerRuntimeFile>, val jdkDirectories: List<OwnerRuntimeDirectory>) {
    val lib: ModelCompilation get() = graph.compilations.single { it.id == ":lib||main" }
    val runtime: List<ModelArtifact> get() = lib.toolchain!!.compilerArtifacts
    val classpath: List<ModelArtifact> get() = lib.classpath
    val plugins: List<ModelArtifact> get() = lib.toolchain!!.compilerPlugins
    fun classify(graph: GradleProjectExport = this.graph, snapshot: SnapshotManifest = this.snapshot,
        bindings: OwnerJvmClassifierBindings = this.bindings, provenance: OwnerJvmPlanProvenance = this.provenance,
        semantics: OwnerJvmLaunchSemantics = OwnerJvmLaunchSemantics.INTENDED_KGP_TASK_CONFIGURATION) =
        OwnerFixtureJvmExecutableInputs.classify(contract, sourceContract, graph, snapshot, bindings, provenance, semantics)
    fun changeLib(change: (ModelCompilation) -> ModelCompilation) = graph.copy(compilations = graph.compilations.map { if (it.id == lib.id) change(it) else it })
    fun rejectArguments(arguments: List<String>) = assertIs<OwnerJvmPlanResult.Rejected>(classify(changeLib { it.copy(compilerArguments = arguments) })).problem
    fun syntheticModel() = VerifiedProjectModel(ModelNamespace(snapshot.ownerId, snapshot.workspaceId, snapshot.worktreeId, "synthetic-lineage"),
        snapshot, graph, provenance.modelIdentity, graph.issues, Digests.sha256("synthetic only; never imported"))
    fun contexts(model: VerifiedProjectModel) = model.compilationIds.map { id -> model.context(id, sourceContract.digest).let {
        OwnerFixtureContext(id, it.completeness.name.lowercase(), it.context?.fullProject ?: false, it.issues.map { issue -> issue.kind }.distinct())
    } }
    fun syntheticStart(): OwnerFixtureStartupEvidence {
        val digest = Digests.sha256("synthetic START data; never a loaded image")
        val file = ModelFile("synthetic", digest, 1)
        return OwnerFixtureStartupEvidence(1, "synthetic-time", OwnerFixtureSupervisorIdentity("synthetic-protocol", "synthetic-target", digest, digest),
            file, file, file, "synthetic-code-source", emptyList(), listOf(OwnerFixtureStartupInput("authored-executable-input-contract",
                "synthetic-original", "synthetic-preserved", contract.digest, 1)), emptyList())
    }
    companion object {
        fun create(): OwnerJvmSyntheticFixture {
            assumeTrue(java.io.File.pathSeparatorChar == ':' && java.io.File.separatorChar == '/')
            val fixtures = (System.getProperty("ktchecker.model.fixtures")?.let(Path::of)
                ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.map { it.resolve("providers/model-fixtures") }
                    .first { Files.isDirectory(it) }).toRealPath()
            assumeOwnerFixtureAnchoredReads(fixtures)
            val directory = fixtures.resolve("jvm-owner-export")
            val source = OwnerFixtureContract.load(directory)
            val contract = OwnerFixtureExecutableContract.load(directory)
            val base = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("synthetic-owner-jvm-never-created-${UUID.randomUUID()}")
            val runtimeDigest = Digests.sha256("synthetic runtime metadata, not registry authority")
            val startupDigest = Digests.sha256("synthetic startup metadata, not image authority")
            val homeDigest = Digests.sha256("synthetic home metadata, not derivation authority")
            val jdkFiles = listOf(OwnerRuntimeFile("jdk", "bin/java", Digests.sha256("synthetic java bytes, never loaded"), 5, true),
                OwnerRuntimeFile("jdk", "lib/modules", Digests.sha256("synthetic jimage bytes, never loaded"), 7, false),
                OwnerRuntimeFile("jdk", "release", Digests.sha256("synthetic release metadata"), 6, false))
            val directories = listOf(OwnerRuntimeDirectory("jdk", "bin"), OwnerRuntimeDirectory("jdk", "lib"))
            val jdk = OwnerJvmJdkInputs.fromManifest(base.resolve("jdk"), runtimeDigest, jdkFiles, directories)
            val bindings = OwnerJvmClassifierBindings(base.resolve("source"), base.resolve("home"), base.resolve("export-output"), jdk)
            val runtime = ownerJvmPinnedArtifacts(contract.value.compilerRuntimeClasspath, bindings.dependencyHome, 14, "compiler-runtime-classpath")
            val classpath = ownerJvmPinnedArtifacts(contract.value.compileClasspath, bindings.dependencyHome, 2, "compile-classpath")
            val plugins = ownerJvmPinnedArtifacts(source.value.compilerPluginClasspath.map { OwnerJvmFilePin(it.seedRelativePath, it.coordinate, it.digest, it.size) },
                bindings.dependencyHome, 7, "scripting-classpath")
            val data = source.value
            fun members(roots: List<String>) = data.files.filter { file -> roots.any { file.path.startsWith("$it/") } }
            val sets = data.sourceSets.map { ModelSourceSet(it.id, it.moduleId, it.name, it.dependsOn, it.sourceRoots, it.resourceRoots,
                members(it.sourceRoots), members(it.resourceRoots)) }
            val pluginToken = "-Xplugin=" + plugins.joinToString(",") { it.path }
            val argv = listOf("-Xallow-no-source-files", "-classpath", classpath.joinToString(":") { it.path }, "-d",
                bindings.ownerExporterOutput.resolve(OwnerFixtureDerivedOutputTree.RELATIVE_PATH).toString(), "-jvm-target", "21", "-module-name",
                "owner-model-fixture:lib", "-no-reflect", "-no-stdlib", pluginToken, bindings.sourceRoot.resolve(contract.value.source.path).toString())
            val graph = GradleProjectExport(gradleVersion = data.gradleVersion,
                modules = data.modules.map { ModelModule(it.id, it.directory, it.buildFile, emptyList()) }, sourceSets = sets,
                compilations = data.compilations.map { expected ->
                    ModelCompilation(expected.id, expected.moduleId, expected.target, expected.platform, expected.name,
                        expected.defaultSourceSetId, expected.sourceSetIds, sets.filter { it.id in expected.sourceSetIds }.flatMap { it.sources }.sortedBy { it.path },
                        expected.compileTaskPath, expected.dependencyConfiguration, classpath, expected.projectProducerIds.map { producer ->
                            ModelProjectDependency(producer.substringBefore('|'), "apiElements", emptyMap(), producer)
                        }, ModelToolchain("jvm", "2.4.21", runtime, jdk.files, plugins), if (expected.id == ":lib||main") argv else listOf(pluginToken),
                        null, null, producedVariants = if (expected.id == ":lib||main") listOf("apiElements") else emptyList(),
                        associatedCompilationIds = expected.associatedCompilationIds)
                }, configurationInputs = data.files.filter { it.path in data.configurationPaths }, generatedInputs = emptyList(), buildscriptArtifacts = emptyList(),
                includedBuilds = emptyList(), issues = listOf(ModelIssue("artifact_unavailable", null, "Synthetic original-kind missing output"),
                    ModelIssue("produced_variants_unavailable", null, "Synthetic original-kind variant gap"),
                    ModelIssue("compiler_arguments_unmodeled", null, "Synthetic original-kind plugin metadata/operation gap")))
            val snapshot = SnapshotManifest(Digests.sha256("synthetic complete fixed fixture metadata"), "synthetic-workspace", "synthetic-worktree",
                data.files.map { SnapshotFile(it.path, it.digest, it.size) }, ownerId = "synthetic-owner")
            val identity = ProjectModelIdentity(snapshot.snapshotId, Digests.sha256("synthetic source identity"), Digests.sha256("synthetic model identity"),
                Digests.sha256("synthetic dependency identity"), Digests.sha256("synthetic toolchain identity"), Digests.sha256("synthetic exporter identity"),
                ownerFixtureRuntimeIdentity(runtimeDigest, startupDigest, homeDigest), source.digest)
            val provenance = OwnerJvmPlanProvenance(OwnerJvmComparisonOrigin.SYNTHETIC_DATA_ONLY, snapshot.snapshotId, identity,
                Digests.sha256("synthetic intended report, never executed"), "synthetic-exporter-id", source.digest, runtimeDigest, startupDigest, homeDigest)
            return OwnerJvmSyntheticFixture(directory, source, contract, snapshot, graph, bindings, provenance, jdkFiles, directories)
        }
    }
}

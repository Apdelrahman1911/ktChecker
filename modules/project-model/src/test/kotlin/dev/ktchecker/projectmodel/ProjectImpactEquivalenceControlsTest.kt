package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.*

/**
 * Deterministic graph-impact controls, NOT provider-findings/full-analysis equivalence tests.
 *
 * ModelFixtures supplies real local SnapshotStore captures and SYNTHETIC owner ingress; no
 * Gradle, compiler, provider, process, successful analysis, or authenticated retirement is run
 * or inferred here. Literal scope tables are independent of both impact planners. Evidence
 * expectations select explicitly OLD/NEW imported input records, never planner-selected scope.
 * These checks do not independently prove importer correctness or cryptographic primitives.
 *
 * A history is a bounded sequence of pairwise impact requests, not persistent cache/retirement
 * proof. Topology changes deliberately widen; tests on an unchanged alternate graph separately
 * check its narrower reachability. OLD-only/NEW-only role changes are topology-masked controls,
 * not proof of a precise edge-union optimization. Scope keys include all RuleContext fields.
 */
class ProjectImpactEquivalenceControlsTest {
    private data class Key(val compilationId: String, val scope: RuleContext)
    private data class Edit(val path: String, val sourceSets: Set<String>, val compilations: Set<String>)
    private data class GraphCase(val name: String, val transform: (GradleProjectExport) -> GradleProjectExport, val edits: List<Edit>)
    private data class RoleCase(val name: String, val path: String, val reasons: Set<String>)

    private val fixtureScopes = mapOf(
        LIB_JVM to RuleContext("jvm", "jvmMain", ":lib", "main"),
        LIB_DESKTOP to RuleContext("desktop", "desktopMain", ":lib", "main"),
        LIB_TEST to RuleContext("jvm", "jvmTest", ":lib", "test"),
        FEATURE to RuleContext("jvm", "jvmMain", ":feature", "main"),
        APP to RuleContext("jvm", "jvmMain", ":app", "main"),
        INDEPENDENT to RuleContext("jvm", "jvmMain", ":independent", "main"),
    )
    private val allSourceSets = setOf(COMMON_SET, SHARED_SET, JVM_SET, DESKTOP_SET, TEST_SET, FEATURE_SET, APP_SET, INDEPENDENT_SET)
    private val libraryConsumers = setOf(LIB_JVM, LIB_DESKTOP, LIB_TEST, FEATURE, APP)
    private val commonDescendants = setOf(COMMON_SET, SHARED_SET, JVM_SET, DESKTOP_SET)
    private val edits = listOf(
        Edit(COMMON_FILE, commonDescendants, libraryConsumers),
        Edit(RESOURCE_FILE, commonDescendants, libraryConsumers),
        Edit(SHARED_FILE, setOf(SHARED_SET, JVM_SET, DESKTOP_SET), libraryConsumers),
        Edit(JVM_FILE, setOf(JVM_SET), setOf(LIB_JVM, LIB_TEST, FEATURE, APP)),
        Edit(DESKTOP_FILE, setOf(DESKTOP_SET), setOf(LIB_DESKTOP)),
        Edit(TEST_FILE, setOf(TEST_SET), setOf(LIB_TEST)),
        Edit(FEATURE_FILE, setOf(FEATURE_SET), setOf(FEATURE, APP)),
        Edit(APP_FILE, setOf(APP_SET), setOf(APP)),
        Edit(INDEPENDENT_FILE, setOf(INDEPENDENT_SET), setOf(INDEPENDENT)),
    )

    @Test fun nineLiteralContentHistoriesPreserveExactScopesEvidenceAndRestorationObligations() {
        val f = ModelFixtures(); val files = f.files(); val initial = f.import(f.capture(files))
        verified("initial verified graph", null, initial, files.keys, allSourceSets, fixtureScopes,
            setOf("initial_model_requires_all_consumers", "executable_build_configuration_changed", "changed_input_membership_unknown", CONFIGURATION_ROLE))
        edits.forEach { edit ->
            val newText = if (edit.path == RESOURCE_FILE) "resource-two\n" else files.getValue(edit.path) + "\n// bounded content control\n"
            val capture = f.capture(files + (edit.path to newText))
            val changed = f.import(capture)
            val forward = verified(edit.path, initial, changed, setOf(edit.path), edit.sourceSets, scopes(edit.compilations))
            val repeated = f.import(capture)
            verified("unchanged ${edit.path}", changed, repeated, emptySet(), emptySet(), emptyMap())
            val restored = f.import(f.capture(files))
            val reverse = verified("restore ${edit.path}", changed, restored, setOf(edit.path), edit.sourceSets, scopes(edit.compilations))
            assertEquals(initial.identity.modelDigest, restored.identity.modelDigest)
            assertEquals(keyed(forward.obligations).keys, keyed(reverse.obligations).keys)
            assertTrue(forward.obligations.all { it.state == "pending" })
            assertTrue(reverse.obligations.all { it.state == "pending" }, "Restoring bytes is new work, not retirement")
        }
    }

    @Test fun allSixOrdersOfThreeEditsHaveTheSameEndpointButOnlyTheirOwnPerStepScopes() {
        val f = ModelFixtures(); val files = f.files(); val baseline = f.import(f.capture(files))
        val selected = listOf(edits.single { it.path == JVM_FILE }, edits.single { it.path == FEATURE_FILE }, edits.single { it.path == INDEPENDENT_FILE })
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        var endpoint: Map<Key, CompilationObligation>? = null
        orders.forEach { order ->
            var contents = files
            var before = baseline
            order.forEach { index ->
                val edit = selected[index]
                contents = contents + (edit.path to (files.getValue(edit.path) + "\n// endpoint edit $index\n"))
                val after = f.import(f.capture(contents))
                verified("order=$order step=$index", before, after, setOf(edit.path), edit.sourceSets, scopes(edit.compilations))
                before = after
            }
            val combined = verified("endpoint $order", baseline, before, selected.map { it.path }.toSet(),
                setOf(JVM_SET, FEATURE_SET, INDEPENDENT_SET), scopes(setOf(LIB_JVM, LIB_TEST, FEATURE, APP, INDEPENDENT)))
            val records = keyed(combined.obligations)
            endpoint?.let { assertEquals(it, records, "Same immutable endpoint facts must not depend on edit order") }
            endpoint = records
        }
        assertNotNull(endpoint)
    }

    @Test fun fileCreationCrossModuleRenameAndDeletionWidenWithoutRetiringAnyCompilation() {
        val f = ModelFixtures(); val files = f.files(); val original = f.import(f.capture(files))
        val createdPath = "independent/src/jvmMain/kotlin/Created.kt"
        val renamedPath = "app/src/jvmMain/kotlin/Renamed.kt"
        val contents = "fun createdValue() = 7\n"
        val created = f.import(f.capture(files + (createdPath to contents)))
        verified("file create", original, created, setOf(createdPath), allSourceSets, fixtureScopes, setOf(TOPOLOGY))
        val renamed = f.import(f.capture(files + (renamedPath to contents)))
        val renamePlan = verified("same bytes new path and module", created, renamed, setOf(createdPath, renamedPath),
            allSourceSets, fixtureScopes, setOf(TOPOLOGY))
        assertEquals(created.snapshot.files.single { it.path == createdPath }.digest, renamed.snapshot.files.single { it.path == renamedPath }.digest)
        val deleted = f.import(f.capture(files))
        val deletionPlan = verified("file delete", renamed, deleted, setOf(renamedPath), allSourceSets, fixtureScopes, setOf(TOPOLOGY))
        assertEquals(original.identity.modelDigest, deleted.identity.modelDigest)
        assertTrue((renamePlan.obligations + deletionPlan.obligations).all { it.state == "pending" })
    }

    @Test fun compilationCreationIdRenameAndDeletionKeepOldIdsAndCurrentPolicyDistinct() {
        val f = ModelFixtures(); val capture = f.capture(); val graph = f.graph(capture); val original = f.import(capture, graph)
        val createdId = ":independent|jvm|control"; val renamedId = ":independent|jvm|renamed-control"
        val extraScope = RuleContext("jvm", "jvmMain", ":independent", "control")
        val template = graph.compilations.single { it.id == INDEPENDENT }
        val addedGraph = graph.copy(compilations = graph.compilations + template.copy(id = createdId, name = "control", compileTaskPath = ":independent:compile-control"))
        val added = f.import(capture, addedGraph)
        val graphReasons = setOf(DEPENDENCY, TOOLCHAIN, TOPOLOGY)
        verified("compilation create", original, added, emptySet(), allSourceSets, fixtureScopes + (createdId to extraScope), graphReasons)
        val newPolicy = Digests.sha256("SYNTHETIC policy at compilation ID rename")
        val renamedGraph = addedGraph.copy(compilations = addedGraph.compilations.map { if (it.id == createdId) it.copy(id = renamedId) else it })
        val renamed = f.import(capture, renamedGraph, configuration = newPolicy)
        val rename = verified("compilation ID rename", added, renamed, emptySet(), allSourceSets, fixtureScopes + (renamedId to extraScope),
            graphReasons + OWNER_CONFIGURATION, removed = mapOf(createdId to extraScope))
        assertEquals(setOf(createdId, renamedId), rename.obligations.filter { it.context == extraScope }.map { it.compilationId }.toSet())
        val deleted = f.import(capture, graph, configuration = newPolicy)
        verified("compilation delete", renamed, deleted, emptySet(), allSourceSets, fixtureScopes, graphReasons, removed = mapOf(renamedId to extraScope))
        // Only the immediate OLD+NEW pair is claimed; persistent retirement debt is owned elsewhere.
    }

    @Test fun fixedAlternateGraphsExerciseProjectAssociationAndRefinementEdgesWithoutTopologyMasking() {
        val cases = listOf(
            GraphCase("project producer retarget", { graph -> graph.copy(compilations = graph.compilations.map { compilation ->
                if (compilation.id == FEATURE) compilation.copy(projectDependencies = listOf(ModelProjectDependency(":lib", "desktopApiElements",
                    mapOf("platform" to "jvm"), LIB_DESKTOP))) else compilation
            }) }, listOf(Edit(JVM_FILE, setOf(JVM_SET), setOf(LIB_JVM, LIB_TEST)), Edit(DESKTOP_FILE, setOf(DESKTOP_SET), setOf(LIB_DESKTOP, FEATURE, APP)))),
            GraphCase("association retarget", { graph -> graph.copy(compilations = graph.compilations.map {
                if (it.id == LIB_TEST) it.copy(associatedCompilationIds = listOf(LIB_DESKTOP)) else it
            }) }, listOf(Edit(JVM_FILE, setOf(JVM_SET), setOf(LIB_JVM, FEATURE, APP)), Edit(DESKTOP_FILE, setOf(DESKTOP_SET), setOf(LIB_DESKTOP, LIB_TEST)))),
            GraphCase("project edge deletion", { graph -> graph.copy(compilations = graph.compilations.map {
                if (it.id == FEATURE) it.copy(projectDependencies = emptyList()) else it
            }) }, listOf(Edit(JVM_FILE, setOf(JVM_SET), setOf(LIB_JVM, LIB_TEST)), Edit(FEATURE_FILE, setOf(FEATURE_SET), setOf(FEATURE, APP)))),
            GraphCase("refinement edge deletion", { graph -> graph.copy(sourceSets = graph.sourceSets.map {
                if (it.id == SHARED_SET) it.copy(dependsOn = emptyList()) else it
            }) }, listOf(Edit(COMMON_FILE, setOf(COMMON_SET), libraryConsumers), Edit(SHARED_FILE, setOf(SHARED_SET, JVM_SET, DESKTOP_SET), libraryConsumers))),
        )
        val f = ModelFixtures(); val files = f.files(); val capture = f.capture(files); val original = f.import(capture)
        cases.forEach { case ->
            val alternate = f.import(capture, case.transform(f.graph(capture)))
            verified("topology forward ${case.name}", original, alternate, emptySet(), allSourceSets, fixtureScopes, setOf(TOPOLOGY))
            verified("topology reverse ${case.name}", alternate, original, emptySet(), allSourceSets, fixtureScopes, setOf(TOPOLOGY))
            verified("fixed graph no-op ${case.name}", alternate, alternate, emptySet(), emptySet(), emptyMap())
            case.edits.forEach { edit ->
                val next = f.capture(files + (edit.path to (files.getValue(edit.path) + "\n// fixed alternate graph\n")))
                val after = f.import(next, case.transform(f.graph(next)))
                verified("unmasked ${case.name} ${edit.path}", alternate, after, setOf(edit.path), edit.sourceSets, scopes(edit.compilations))
            }
        }
    }

    @Test fun identityAndCaptureChangesRebindAllExactEvidenceRatherThanOnlyObligationCounts() {
        val f = ModelFixtures(); val capture = f.capture(); val graph = f.graph(capture); val before = f.import(capture, graph)
        val policy = f.import(capture, graph, configuration = Digests.sha256("SYNTHETIC revised policy"))
        verified("policy", before, policy, emptySet(), allSourceSets, fixtureScopes, setOf(OWNER_CONFIGURATION))
        val runtime = f.import(capture, graph, runtime = Digests.sha256("SYNTHETIC revised Gradle runtime"))
        verified("runtime", before, runtime, emptySet(), allSourceSets, fixtureScopes, setOf("gradle_runtime_identity_changed"))
        val dependency = f.import(capture, graph.copy(compilations = graph.compilations.map { if (it.id == INDEPENDENT) it.copy(classpath = emptyList()) else it }))
        verified("dependency", before, dependency, emptySet(), allSourceSets, fixtureScopes, setOf(DEPENDENCY, TOPOLOGY))
        val toolchain = f.import(capture, graph.copy(compilations = graph.compilations.map {
            if (it.id == INDEPENDENT) it.copy(toolchain = it.toolchain!!.copy(compilerVersion = "2.4.22")) else it
        }))
        verified("toolchain", before, toolchain, emptySet(), allSourceSets, fixtureScopes, setOf(TOOLCHAIN, TOPOLOGY))
        val arguments = f.import(capture, graph.copy(compilations = graph.compilations.map { if (it.id == INDEPENDENT) it.copy(compilerArguments = it.compilerArguments + "-progressive") else it }))
        verified("arguments", before, arguments, emptySet(), allSourceSets, fixtureScopes, setOf(TOPOLOGY))
        val missing = f.import(capture, graph.copy(compilations = graph.compilations.map { if (it.id == INDEPENDENT) it.copy(toolchain = null) else it }))
        verified("missing toolchain", before, missing, emptySet(), allSourceSets, fixtureScopes, setOf(TOOLCHAIN, TOPOLOGY, UNKNOWN_MODEL),
            state = "unsupported", issues = setOf("toolchain_unavailable"))
        verified("recovered model still requires work", missing, before, emptySet(), allSourceSets, fixtureScopes, setOf(TOOLCHAIN, TOPOLOGY, UNKNOWN_MODEL))
        val unmapped = f.import(capture, graph.copy(compilations = graph.compilations.map { compilation ->
            if (compilation.id == FEATURE) compilation.copy(projectDependencies = compilation.projectDependencies.map { it.copy(compilationId = null) }) else compilation
        }))
        val unknownProducer = "unknown_producer_variant_requires_full_relevant_graph"
        verified("unmapped producer", before, unmapped, emptySet(), allSourceSets, fixtureScopes, setOf(TOPOLOGY, UNKNOWN_MODEL, unknownProducer),
            state = "unsupported", issues = setOf("project_variant_unmapped"))
        verified("unchanged incomplete graph still blocks", unmapped, unmapped, emptySet(), allSourceSets, fixtureScopes, setOf(UNKNOWN_MODEL, unknownProducer),
            state = "unsupported", issues = setOf("project_variant_unmapped"))
        val metadata = listOf(
            Triple("declared roots", capture.snapshot.copy(declaredRoots = listOf("different-owner-declared-root")), emptySet<String>()),
            Triple("capture provenance", capture.snapshot.copy(captureKind = "coordinated_capture"), emptySet<String>()),
            Triple("unknown capture input", capture.snapshot.copy(unknownInputs = listOf("SYNTHETIC unknown input")), setOf("snapshot_input_unknown")),
            Triple("capture exclusion", capture.snapshot.copy(scopeExclusions = listOf("SYNTHETIC omitted input")), setOf("snapshot_scope_excluded")),
        )
        metadata.forEach { (name, snapshot, issues) ->
            // As in ModelFixtures' existing metadata controls, these are synthetic owner choices,
            // not attestations that this test discovered an external configuration read closure.
            val after = f.import(capture.copy(snapshot = snapshot), graph)
            verified(name, before, after, emptySet(), allSourceSets, fixtureScopes,
                setOf(CAPTURE) + if (issues.isEmpty()) emptySet() else setOf(UNKNOWN_MODEL),
                state = if (issues.isEmpty()) "pending" else "unsupported", issues = issues)
        }
        val changedBuild = f.capture(f.files() + ("settings.gradle.kts" to "rootProject.name = \"changed-control\"\n"))
        verified("executable configuration", before, f.import(changedBuild), setOf("settings.gradle.kts"), allSourceSets, fixtureScopes,
            setOf("executable_build_configuration_changed", "changed_input_membership_unknown", CONFIGURATION_ROLE))
        val opaque = f.capture(f.files() + ("opaque-input.bin" to "SYNTHETIC unowned capture input"))
        verified("unknown membership", before, f.import(opaque), setOf("opaque-input.bin"), allSourceSets, fixtureScopes, setOf("changed_input_membership_unknown"))
    }

    @Test fun reusedIdRetargetsEachRuleContextFieldAndRetainsOldEvidenceUnderTheNewPolicy() {
        val f = ModelFixtures(); val files = f.files(); val capture = f.capture(files)
        val before = f.import(capture, retargetFixtureGraph(f.graph(capture)))
        val oldScope = RuleContext("jvm", "jvmMain", ":independent", "isolation")
        val retainedScope = RuleContext("jvm", "jvmMain", ":independent", "retained")
        val beforeScopes = fixtureScopes + (INDEPENDENT to oldScope) + (RETAINED to retainedScope)
        val retargetSets = allSourceSets + ALTERNATE_SET
        val next = f.capture(files + (INDEPENDENT_FILE to (files.getValue(INDEPENDENT_FILE) + "\n// changed retarget evidence\n")))
        val nextGraph = retargetFixtureGraph(f.graph(next))
        val sameScope = f.import(next, nextGraph)
        verified("same scope content guard", before, sameScope, setOf(INDEPENDENT_FILE), setOf(INDEPENDENT_SET),
            mapOf(INDEPENDENT to oldScope, RETAINED to retainedScope))
        val destinations = listOf(
            "target" to RuleContext("otherJvm", "jvmMain", ":independent", "isolation"),
            "source set" to RuleContext("jvm", "alternateMain", ":independent", "isolation"),
            "module" to RuleContext("jvm", "jvmMain", ":app", "isolation"),
            "compilation" to RuleContext("jvm", "jvmMain", ":independent", "otherIsolation"),
        )
        destinations.forEach { (field, scope) ->
            val changedGraph = nextGraph.copy(compilations = nextGraph.compilations.map { original ->
                if (original.id != INDEPENDENT) original else {
                    val moved = when (field) {
                        "target" -> original.copy(target = scope.target)
                        "source set" -> original.copy(defaultSourceSetId = ALTERNATE_SET, sourceSetIds = original.sourceSetIds + ALTERNATE_SET)
                        "module" -> original.copy(moduleId = ":app", defaultSourceSetId = APP_SET, sourceSetIds = listOf(APP_SET),
                            sourceFiles = nextGraph.sourceSets.single { it.id == APP_SET }.sources)
                        "compilation" -> original.copy(name = scope.compilation)
                        else -> error("Unlisted bounded retarget")
                    }
                    moved.copy(classpath = emptyList(), toolchain = moved.toolchain!!.copy(compilerVersion = "2.4.22"))
                }
            })
            val policy = Digests.sha256("SYNTHETIC retarget policy $field")
            val after = f.import(next, changedGraph, configuration = policy)
            val plan = verified("retarget $field", before, after, setOf(INDEPENDENT_FILE), retargetSets, beforeScopes + (INDEPENDENT to scope),
                setOf(OWNER_CONFIGURATION, DEPENDENCY, TOOLCHAIN, TOPOLOGY), retargeted = mapOf(INDEPENDENT to oldScope))
            val old = plan.obligations.single { it.compilationId == INDEPENDENT && it.state == "removed_unresolved" }
            val current = plan.obligations.single { it.compilationId == INDEPENDENT && it.state == "pending" }
            assertNotEquals(old.compilationFilesDigest, current.compilationFilesDigest, field)
            assertNotEquals(old.modelDigest, current.modelDigest, field)
            assertNotEquals(old.dependencyDigest, current.dependencyDigest, field)
            assertNotEquals(old.toolchainDigest, current.toolchainDigest, field)
            assertEquals(policy, old.configurationDigest, field)
            assertEquals(1, listOf(oldScope.target != scope.target, oldScope.sourceSet != scope.sourceSet,
                oldScope.module != scope.module, oldScope.compilation != scope.compilation).count { it }, field)
        }
    }

    @Test fun overlappingNonSourceRolesWidenOnBytesButNotOnRolePresenceOrDisjointEdits() {
        val cases = listOf(
            RoleCase("auxiliary", RESOURCE_FILE, setOf(AUXILIARY_ROLE)),
            RoleCase("configuration", RESOURCE_FILE, setOf(CONFIGURATION_ROLE)),
            RoleCase("generated input", RESOURCE_FILE, setOf(GENERATED_INPUT_ROLE)),
            RoleCase("generated output", COMMON_FILE, setOf(GENERATED_OUTPUT_ROLE)),
            RoleCase("generated input and output", RESOURCE_FILE, setOf(GENERATED_INPUT_ROLE, GENERATED_OUTPUT_ROLE)),
        )
        val f = ModelFixtures(); val files = f.files(); val capture = f.capture(files); val ordinary = f.import(capture)
        cases.forEach { case ->
            val before = f.import(capture, roleGraph(f.graph(capture), case.name))
            val repeatedImport = f.import(capture, roleGraph(f.graph(capture), case.name))
            verified("unchanged role ${case.name}", before, repeatedImport, emptySet(), emptySet(), emptyMap())
            val disjointCapture = f.capture(files + (APP_FILE to (files.getValue(APP_FILE) + "\n// disjoint from every tested role\n")))
            val disjoint = f.import(disjointCapture, roleGraph(f.graph(disjointCapture), case.name))
            verified("untouched role ${case.name}", before, disjoint, setOf(APP_FILE), setOf(APP_SET), scopes(setOf(APP)))
            val sameLengthBytes = when (case.path) {
                RESOURCE_FILE -> "resource-two\n"
                COMMON_FILE -> "fun commonValue() = 2\n"
                else -> error("Unlisted bounded role edit")
            }
            val next = f.capture(files + (case.path to sameLengthBytes))
            val after = f.import(next, roleGraph(f.graph(next), case.name))
            assertEquals(before.snapshot.files.single { it.path == case.path }.size, after.snapshot.files.single { it.path == case.path }.size)
            assertNotEquals(before.snapshot.files.single { it.path == case.path }.digest, after.snapshot.files.single { it.path == case.path }.digest)
            // Role membership is identical. No topology or extension heuristic may hide this check.
            verified("stable overlapping role ${case.name}", before, after, setOf(case.path), allSourceSets, fixtureScopes, case.reasons)
            verified("reverse stable role ${case.name}", after, before, setOf(case.path), allSourceSets, fixtureScopes, case.reasons)
            val bareAfter = f.import(next)
            // Topology already widens these two; the exact role reason only tests OLD/NEW detection.
            verified("OLD-only role ${case.name}", before, bareAfter, setOf(case.path), allSourceSets, fixtureScopes, case.reasons + TOPOLOGY)
            verified("NEW-only role ${case.name}", ordinary, after, setOf(case.path), allSourceSets, fixtureScopes, case.reasons + TOPOLOGY)
        }
    }

    @Test fun namespaceAdmissionAndBidirectionalLineageImpactIsolationDoNotSpliceModels() {
        val f = ModelFixtures(); val capture = f.capture(); val graph = f.graph(capture)
        val complete = f.import(capture, graph); val conditional = observed(f, capture, graph)
        val namespaces = listOf(f.namespace.copy(ownerId = "other-owner"), f.namespace.copy(workspaceId = "other-workspace"), f.namespace.copy(worktreeId = "other-worktree"))
        namespaces.forEach { namespace ->
            assertEquals("model_namespace_mismatch", assertOwnerFixtureModelFailure(f.work) { importNamespace(f, capture, graph, namespace) }.code)
            assertEquals("model_namespace_mismatch", assertOwnerFixtureModelFailure(f.work) { observed(f, capture, graph, namespace = namespace) }.code)
        }
        // The three cases above are IMPORT-gate coverage. Only lineage crosses a real import
        // here; they are not advertised as independent planner-level tests of all four fields.
        val otherLineage = f.namespace.copy(lineageId = "other-lineage")
        val otherComplete = importNamespace(f, capture, graph, otherLineage)
        val otherConditional = observed(f, capture, graph, namespace = otherLineage)
        listOf(complete to otherComplete, otherComplete to complete).forEach { (before, after) ->
            assertEquals("impact_namespace_mismatch", assertFailsWith<ProjectModelException> { ProjectImpactPlanner().impact(before, after) }.code)
        }
        listOf(conditional to otherConditional, otherConditional to conditional).forEach { (before, after) ->
            assertEquals("impact_namespace_mismatch", assertFailsWith<ProjectModelException> { ObservedProjectImpactPlanner().impact(before, after) }.code)
        }
        verified("same namespace remains admissible", complete, complete, emptySet(), emptySet(), emptyMap())
        observedPlan("same namespace observed remains conservative", conditional, conditional, emptySet(), fixtureScopes)
    }

    @Test fun observedHistoriesNeverNarrowReuseOrTurnRemovedAndRetargetedEvidenceIntoCompletion() {
        val f = ModelFixtures(); val files = f.files(); val capture = f.capture(files)
        assumeOwnerFixtureAnchoredReads(f.work)
        val initial = observed(f, capture)
        observedPlan("initial observed", null, initial, files.keys, fixtureScopes)
        val unchanged = observed(f, capture)
        observedPlan("new observation same bytes", initial, unchanged, emptySet(), fixtureScopes)
        assertNotEquals(initial.evidenceIdentity, unchanged.evidenceIdentity)
        val next = f.capture(files + (INDEPENDENT_FILE to (files.getValue(INDEPENDENT_FILE) + "\n// conditional independent edit\n")))
        val edited = observed(f, next)
        observedPlan("observed independent edit still all", unchanged, edited, setOf(INDEPENDENT_FILE), fixtureScopes)
        val graph = f.graph(next)
        val policy = Digests.sha256("SYNTHETIC observed replacement policy")
        val replacementScope = RuleContext("otherJvm", "jvmMain", ":independent", "main")
        val retargetGraph = graph.copy(compilations = graph.compilations.map {
            if (it.id == INDEPENDENT) it.copy(target = "otherJvm", classpath = emptyList(), toolchain = it.toolchain!!.copy(compilerVersion = "2.4.22")) else it
        })
        val retargeted = observed(f, next, retargetGraph, policy)
        observedPlan("observed same-ID retarget", edited, retargeted, emptySet(), fixtureScopes + (INDEPENDENT to replacementScope), retargeted = scopes(setOf(INDEPENDENT)))
        val removedGraph = retargetGraph.copy(compilations = retargetGraph.compilations.filterNot { it.id == INDEPENDENT },
            sourceSets = retargetGraph.sourceSets.filterNot { it.id == INDEPENDENT_SET })
        val removed = observed(f, next, removedGraph, policy)
        observedPlan("observed removal", retargeted, removed, emptySet(), fixtureScopes - INDEPENDENT, removed = mapOf(INDEPENDENT to replacementScope))
        val restored = observed(f, capture)
        observedPlan("observed restore remains all", removed, restored, setOf(INDEPENDENT_FILE), fixtureScopes)
        listOf(initial, unchanged, edited, retargeted, removed, restored).forEach { model ->
            model.compilationIds.forEach { id ->
                val context = model.context(id, model.identity.configurationDigest)
                assertEquals(ModelCompleteness.UNAVAILABLE, context.completeness)
                assertNull(context.certificateId)
                val analysis = assertNotNull(context.context)
                assertFalse(analysis.fullProject)
                assertEquals(CONFIGURATION_INPUT_CLOSURE_UNPROVEN, analysis.policies[OBSERVED_CLOSURE_POLICY])
            }
        }
    }

    @Test fun ambiguousExportedScopeLabelsStayUnsupportedAndCannotEraseEitherNominalHistory() {
        val f = ModelFixtures(); val files = f.files(); val capture = f.capture(files)
        val graph = ambiguousFixtureGraph(f.graph(capture))
        val before = f.import(capture, graph)
        val observedBefore = observed(f, capture, graph)
        val oldScope = RuleContext("jvm", "jvmMain", ":independent", "main")
        val newScope = RuleContext("otherJvm", "jvmMain", ":independent", "main")
        val oldLabels = mapOf(INDEPENDENT to oldScope, ALIAS to oldScope)
        val newLabels = mapOf(INDEPENDENT to newScope, ALIAS to newScope)
        val unambiguous = scopes(libraryConsumers)
        val policy = Digests.sha256("SYNTHETIC ambiguous retarget policy")
        val changedGraph = graph.copy(compilations = graph.compilations.map {
            if (it.id in oldLabels) it.copy(target = "otherJvm") else it
        })
        val after = f.import(capture, changedGraph, configuration = policy)
        val observedAfter = observed(f, capture, changedGraph, policy)
        assertTrue(before.issues.isEmpty()); assertTrue(after.issues.isEmpty())
        val global = setOf(OWNER_CONFIGURATION, TOPOLOGY)
        val expectedVerified = expectedRows(after.export, after.identity, unambiguous, policy, "pending", global) +
            labelRows(after.export, after.identity, newLabels, policy, "unsupported", global) +
            labelRows(before.export, before.identity, oldLabels, policy, "removed_unresolved", global + RETARGET_REASONS)
        verifiedRecords("ambiguous verified retarget", before, after, emptySet(), allSourceSets + ALIAS_SET, global, expectedVerified)
        val observedReasons = setOf(CONFIGURATION_INPUT_CLOSURE_UNPROVEN, "observed_export_requires_all_before_after_contexts")
        val expectedObserved = expectedRows(observedAfter.export, observedAfter.identity, unambiguous, policy, "unsupported", observedReasons) +
            labelRows(observedAfter.export, observedAfter.identity, newLabels, policy, "unsupported", observedReasons) +
            labelRows(observedBefore.export, observedBefore.identity, oldLabels, policy, "removed_unresolved", observedReasons + RETARGET_REASONS)
        observedRecords("ambiguous observed retarget", observedBefore, observedAfter, emptySet(), allSourceSets + ALIAS_SET, expectedObserved)

        // A changed source digest with the SAME two ambiguous tuples does not invent two old
        // contexts. This guard still requires the affected label to remain UNSUPPORTED.
        val next = f.capture(files + (INDEPENDENT_FILE to (files.getValue(INDEPENDENT_FILE) + "\n// same ambiguous scope\n")))
        val sameGraph = ambiguousFixtureGraph(f.graph(next))
        val sameScope = f.import(next, sameGraph)
        verifiedRecords("ambiguous verified same-scope content", before, sameScope, setOf(INDEPENDENT_FILE), setOf(INDEPENDENT_SET), emptySet(),
            labelRows(sameScope.export, sameScope.identity, mapOf(INDEPENDENT to oldScope), f.configurationDigest, "unsupported", emptySet()))
        val sameObserved = observed(f, next, sameGraph)
        observedRecords("ambiguous observed same-scope content", observedBefore, sameObserved, setOf(INDEPENDENT_FILE), allSourceSets + ALIAS_SET,
            expectedRows(sameObserved.export, sameObserved.identity, unambiguous, f.configurationDigest, "unsupported", observedReasons) +
                labelRows(sameObserved.export, sameObserved.identity, oldLabels, f.configurationDigest, "unsupported", observedReasons))

        // Exported tuples are routing/audit labels only. Even after retarget they must NOT
        // manufacture AnalysisContext, dependency/toolchain evidence, or complete certificates.
        listOf(before, after, sameScope).forEach { model -> oldLabels.keys.forEach { id ->
            val facts = model.context(id, model.identity.configurationDigest)
            assertEquals(ModelCompleteness.UNSUPPORTED, facts.completeness)
            assertNull(facts.context); assertNull(facts.certificateId)
            assertEquals(setOf("compilation_context_unknown"), facts.issues.map { it.kind }.toSet())
        } }
        listOf(observedBefore, observedAfter, sameObserved).forEach { model -> oldLabels.keys.forEach { id ->
            val facts = model.context(id, model.identity.configurationDigest)
            assertEquals(ModelCompleteness.UNSUPPORTED, facts.completeness)
            assertNull(facts.context); assertNull(facts.certificateId)
            assertEquals(setOf(CONFIGURATION_INPUT_CLOSURE_UNPROVEN, "compilation_context_unknown"), facts.issues.map { it.kind }.toSet())
        } }
    }

    private fun scopes(ids: Collection<String>) = ids.associateWith { fixtureScopes.getValue(it) }

    /** Only a designated input side supplies evidence; expected scope/reachability is literal. */
    private fun expectedRows(graph: GradleProjectExport, identity: ProjectModelIdentity, selections: Map<String, RuleContext>,
                             configuration: String, state: String, reasons: Set<String>): List<CompilationObligation> = selections.map { (id, scope) ->
        val compilation = graph.compilations.single { it.id == id }
        val toolchain = compilation.toolchain?.takeIf { chain -> (chain.compilerArtifacts + chain.runtimeArtifacts + chain.compilerPlugins).all { it.digest != null } }
        CompilationObligation(id, scope, digest(compilation.sourceFiles), identity.modelDigest,
            digest(mapOf("classpath" to compilation.classpath, "friends" to compilation.friendArtifacts, "auxiliary" to compilation.auxiliaryArtifacts)),
            toolchain?.let { digest(it) }, configuration, state, reasons.sorted())
    }

    private fun keyed(rows: List<CompilationObligation>): Map<Key, CompilationObligation> {
        val pairs = rows.map { row ->
            assertEquals(row.reasons.distinct().size, row.reasons.size, "Duplicate evidence reasons")
            Key(row.compilationId, assertNotNull(row.context, "A required RuleContext or explicit audit label must not disappear")) to row.copy(reasons = row.reasons.sorted())
        }
        assertEquals(pairs.size, pairs.map { it.first }.distinct().size, "Duplicate compilation-ID plus four-field RuleContext key")
        return pairs.toMap()
    }

    private fun assertObligationOrder(rows: List<CompilationObligation>, label: String) {
        assertEquals(rows.map { it.compilationId }.sorted(), rows.map { it.compilationId }, label)
        rows.groupBy { it.compilationId }.values.filter { it.size > 1 }.forEach { versions ->
            assertEquals(listOf(false, true), versions.map { it.state == "removed_unresolved" }, "$label current before historical")
        }
    }

    private fun verified(label: String, before: VerifiedProjectModel?, after: VerifiedProjectModel, changed: Collection<String>,
                         sourceSets: Collection<String>, current: Map<String, RuleContext>, reasons: Set<String> = emptySet(),
                         state: String = "pending", issues: Set<String> = emptySet(), removed: Map<String, RuleContext> = emptyMap(),
                         retargeted: Map<String, RuleContext> = emptyMap()): ProjectImpactPlan {
        assertEquals(issues, after.issues.map { it.kind }.toSet(), "$label fixture model gaps")
        val expected: List<CompilationObligation> = expectedRows(after.export, after.identity, current, after.identity.configurationDigest, state, reasons + issues) +
            if (before == null) {
                assertTrue(removed.isEmpty() && retargeted.isEmpty()); emptyList()
            } else {
                expectedRows(before.export, before.identity, removed, after.identity.configurationDigest, "removed_unresolved", reasons + REMOVED) +
                    expectedRows(before.export, before.identity, retargeted, after.identity.configurationDigest, "removed_unresolved", reasons + RETARGET_REASONS)
            }
        return verifiedRecords(label, before, after, changed, sourceSets, reasons, expected)
    }

    private fun verifiedRecords(label: String, before: VerifiedProjectModel?, after: VerifiedProjectModel, changed: Collection<String>,
                                sourceSets: Collection<String>, reasons: Set<String>, expected: List<CompilationObligation>): ProjectImpactPlan {
        val plan = ProjectImpactPlanner().impact(before, after)
        assertEquals(before?.identity, plan.before, label); assertEquals(after.identity, plan.after, label)
        assertEquals(changed.toSet().sorted(), plan.changedFiles, label)
        assertEquals(sourceSets.toSet().sorted(), plan.affectedSourceSetIds, label)
        assertEquals(reasons.sorted(), plan.reasons, label)
        assertEquals(keyed(expected), keyed(plan.obligations), "$label exact obligation fields")
        assertObligationOrder(plan.obligations, label)
        // The full vector has already matched the independent scope/evidence oracle above.
        // Serialize that asserted vector to check before/after ingress and path evidence binding.
        assertEquals(digest(listOf(before?.ingressCertificate.orEmpty(), after.ingressCertificate,
            canonical(changed.toSet().sorted()), canonical(plan.obligations))), plan.certificateId, label)
        assertTrue(Digests.valid(plan.certificateId), label)
        val repeated = ProjectImpactPlanner().impact(before, after)
        assertEquals(plan.obligations, repeated.obligations, label); assertEquals(plan.certificateId, repeated.certificateId, label)
        return plan
    }

    private fun observedPlan(label: String, before: ObservedProjectModel?, after: ObservedProjectModel, changed: Collection<String>,
                             current: Map<String, RuleContext>, removed: Map<String, RuleContext> = emptyMap(),
                             retargeted: Map<String, RuleContext> = emptyMap()): ObservedProjectImpactPlan {
        assertEquals(setOf(CONFIGURATION_INPUT_CLOSURE_UNPROVEN), after.issues.map { it.kind }.toSet(), label)
        val reasons = setOf(CONFIGURATION_INPUT_CLOSURE_UNPROVEN, "observed_export_requires_all_before_after_contexts")
        val expected: List<CompilationObligation> = expectedRows(after.export, after.identity, current, after.identity.configurationDigest, "unsupported", reasons) +
            if (before == null) {
                assertTrue(removed.isEmpty() && retargeted.isEmpty()); emptyList()
            } else {
                expectedRows(before.export, before.identity, removed, after.identity.configurationDigest, "removed_unresolved", reasons + REMOVED) +
                    expectedRows(before.export, before.identity, retargeted, after.identity.configurationDigest, "removed_unresolved", reasons + RETARGET_REASONS)
            }
        return observedRecords(label, before, after, changed, allSourceSets, expected)
    }

    private fun observedRecords(label: String, before: ObservedProjectModel?, after: ObservedProjectModel, changed: Collection<String>,
                                sourceSets: Collection<String>, expected: List<CompilationObligation>): ObservedProjectImpactPlan {
        val reasons = setOf(CONFIGURATION_INPUT_CLOSURE_UNPROVEN, "observed_export_requires_all_before_after_contexts")
        val plan = ObservedProjectImpactPlanner().impact(before, after)
        assertEquals(before?.identity, plan.before, label); assertEquals(after.identity, plan.after, label)
        assertEquals(changed.toSet().sorted(), plan.changedFiles, label)
        assertEquals(sourceSets.toSet().sorted(), plan.affectedSourceSetIds, label)
        assertEquals(reasons, plan.reasons.toSet(), label)
        assertEquals(keyed(expected), keyed(plan.obligations), "$label full conditional records")
        assertObligationOrder(plan.obligations, label)
        assertEquals(digest(listOf(OBSERVED_EXPORT_PROFILE, before?.evidenceIdentity.orEmpty(), after.evidenceIdentity,
            CONFIGURATION_INPUT_CLOSURE_UNPROVEN, digest(changed.toSet().sorted()), digest(plan.obligations))), plan.evidenceIdentity, label)
        val repeated = ObservedProjectImpactPlanner().impact(before, after)
        assertEquals(plan.obligations, repeated.obligations, label); assertEquals(plan.evidenceIdentity, repeated.evidenceIdentity, label)
        return plan
    }

    /** A nominally imported tuple is not an AnalysisContext when actual scope lookup is ambiguous. */
    private fun labelRows(graph: GradleProjectExport, identity: ProjectModelIdentity, labels: Map<String, RuleContext>,
                          configuration: String, state: String, reasons: Set<String>): List<CompilationObligation> =
        expectedRows(graph, identity, labels, configuration, state, reasons + setOf("compilation_context_unknown", "exported_context_label_only"))
            .map { it.copy(dependencyDigest = null, toolchainDigest = null) }

    private fun ambiguousFixtureGraph(graph: GradleProjectExport): GradleProjectExport {
        val sourceSet = graph.sourceSets.single { it.id == INDEPENDENT_SET }
        val compilation = graph.compilations.single { it.id == INDEPENDENT }
        return graph.copy(sourceSets = graph.sourceSets + sourceSet.copy(id = ALIAS_SET, dependsOn = emptyList(),
            sourceRoots = listOf("independent/src/alias-empty/kotlin"), resourceRoots = listOf("independent/src/alias-empty/resources"), sources = emptyList(), resources = emptyList()),
            compilations = graph.compilations + compilation.copy(id = ALIAS, defaultSourceSetId = ALIAS_SET, sourceSetIds = listOf(ALIAS_SET),
                sourceFiles = emptyList(), compileTaskPath = ":independent:compile-alias-control"))
    }

    private fun retargetFixtureGraph(graph: GradleProjectExport): GradleProjectExport {
        val sourceSet = graph.sourceSets.single { it.id == INDEPENDENT_SET }
        val compilation = graph.compilations.single { it.id == INDEPENDENT }
        return graph.copy(sourceSets = graph.sourceSets + sourceSet.copy(id = ALTERNATE_SET, name = "alternateMain", dependsOn = emptyList(),
            sourceRoots = listOf("independent/src/alternateMain/kotlin"), resourceRoots = listOf("independent/src/alternateMain/resources"), sources = emptyList(), resources = emptyList()),
            compilations = graph.compilations.map { if (it.id == INDEPENDENT) it.copy(name = "isolation") else it } +
                compilation.copy(id = RETAINED, name = "retained", compileTaskPath = ":independent:compile-retained"))
    }

    private fun roleGraph(graph: GradleProjectExport, role: String): GradleProjectExport {
        val resource = graph.sourceSets.single { it.id == COMMON_SET }.resources.single()
        val common = graph.sourceSets.single { it.id == COMMON_SET }.sources.single()
        val independent = graph.sourceSets.single { it.id == INDEPENDENT_SET }.sources.single()
        fun generated(input: List<ModelFile>, output: ModelFile, set: String, root: String) = graph.copy(generatedInputs = listOf(
            GeneratedSourceInput(":syntheticRoleControl", listOf(set), input, listOf(output), listOf(root), "captured")))
        return when (role) {
            "auxiliary" -> graph.copy(compilations = graph.compilations.map { if (it.id == INDEPENDENT) it.copy(auxiliaryInputFiles = listOf(resource)) else it })
            "configuration" -> graph.copy(configurationInputs = (graph.configurationInputs + resource).sortedBy { it.path })
            "generated input" -> generated(listOf(resource), independent, INDEPENDENT_SET, "independent/src/jvmMain/kotlin")
            "generated output" -> generated(listOf(resource), common, COMMON_SET, "lib/src/commonMain/kotlin")
            "generated input and output" -> generated(listOf(resource), resource, COMMON_SET, "lib/src/commonMain/resources")
            else -> error("Unlisted bounded role control")
        }
    }

    private fun importNamespace(f: ModelFixtures, capture: ModelFixtures.Capture, graph: GradleProjectExport, namespace: ModelNamespace): VerifiedProjectModel {
        val raw = ContractJson.encodeToString(graph).toByteArray()
        val request = f.request(capture, graph, raw).copy(namespace = namespace)
        return ProjectModelImporter(ExactModelIngressAuthority(f.permit(capture, request))).import(request, raw)
    }

    /** Caller establishes anchored-read applicability OUTSIDE any domain-failure assertion. */
    private fun observed(f: ModelFixtures, capture: ModelFixtures.Capture, graph: GradleProjectExport = f.graph(capture),
                         configuration: String = f.configurationDigest, namespace: ModelNamespace = f.namespace): ObservedProjectModel {
        val invocation = ObservedExportInvocation(OwnedApplicationDeadline.afterMillis(120_000))
        val raw = ContractJson.encodeToString(graph).toByteArray()
        val request = f.request(capture, graph, raw, configuration).copy(namespace = namespace, executionId = invocation.identity)
        val permit = ObservedExportPermit(request, invocation, f.gradleRuntimeDigest,
            Digests.sha256("SYNTHETIC impact controls; no export launch, complete closure, or provider equivalence"), capture.verify,
            { check(request.exportDigest == Digests.sha256(raw)) }, { check(it == f.artifact) }, { check(it == graph) })
        return ObservedProjectModelImporter(ExactObservedExportAuthority(permit)).import(request, raw)
    }

    private inline fun <reified T> canonical(value: T): String = Digests.canonical(ContractJson.encodeToJsonElement(value))
    private inline fun <reified T> digest(value: T): String = Digests.sha256(canonical(value))

    private companion object {
        const val LIB_JVM = ":lib|jvm|main"; const val LIB_DESKTOP = ":lib|desktop|main"; const val LIB_TEST = ":lib|jvm|test"
        const val FEATURE = ":feature|jvm|main"; const val APP = ":app|jvm|main"; const val INDEPENDENT = ":independent|jvm|main"
        const val RETAINED = ":independent|jvm|retained-control"; const val ALTERNATE_SET = ":independent|alternateMain"
        const val ALIAS = ":independent|jvm|alias-control"; const val ALIAS_SET = ":independent|alias-source-set-control"
        const val COMMON_SET = ":lib|commonMain"; const val SHARED_SET = ":lib|sharedMain"; const val JVM_SET = ":lib|jvmMain"
        const val DESKTOP_SET = ":lib|desktopMain"; const val TEST_SET = ":lib|jvmTest"; const val FEATURE_SET = ":feature|jvmMain"
        const val APP_SET = ":app|jvmMain"; const val INDEPENDENT_SET = ":independent|jvmMain"
        const val COMMON_FILE = "lib/src/commonMain/kotlin/Common.kt"; const val RESOURCE_FILE = "lib/src/commonMain/resources/message.txt"
        const val SHARED_FILE = "lib/src/sharedMain/kotlin/Shared.kt"; const val JVM_FILE = "lib/src/jvmMain/kotlin/Jvm.kt"
        const val DESKTOP_FILE = "lib/src/desktopMain/kotlin/Desktop.kt"; const val TEST_FILE = "lib/src/jvmTest/kotlin/Checks.kt"
        const val FEATURE_FILE = "feature/src/jvmMain/kotlin/Feature.kt"; const val APP_FILE = "app/src/jvmMain/kotlin/App.kt"
        const val INDEPENDENT_FILE = "independent/src/jvmMain/kotlin/Independent.kt"
        const val TOPOLOGY = "model_topology_membership_or_arguments_changed"
        const val DEPENDENCY = "resolved_dependency_identity_changed"; const val TOOLCHAIN = "compiler_runtime_or_plugin_identity_changed"
        const val OWNER_CONFIGURATION = "owner_configuration_changed"; const val CAPTURE = "capture_scope_roots_or_provenance_changed"
        const val UNKNOWN_MODEL = "unknown_model_inputs_require_full_relevant_graph"; const val REMOVED = "consumer_removed_without_retirement_evidence"
        const val AUXILIARY_ROLE = "auxiliary_input_changed_requires_full_relevant_graph"
        const val CONFIGURATION_ROLE = "configuration_input_changed_requires_full_relevant_graph"
        const val GENERATED_INPUT_ROLE = "generated_input_changed_requires_full_relevant_graph"
        const val GENERATED_OUTPUT_ROLE = "generated_output_changed_requires_full_relevant_graph"
        val RETARGET_REASONS = setOf("required_context_retargeted_unresolved", "consumer_context_changed_without_retirement_evidence")
    }
}

package dev.ktchecker.architecture

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class ArchitecturePolicyProviderTest {
    @Test fun missingOwnerPolicyAndJsonApprovalNeverActivateConventionalArchitecture(): Unit {
        val fixture = ArchitectureFixtures(); val model = fixture.model(listOf(PolicyEdge(":billing", ":catalog")))
        val policy = fixture.bundle(DeclaredArchitecturePolicy.AllowedEdges(fixture.scope(), DefaultEdgePolicy.FORBID, emptyList(), emptyList()))
        assertEquals("architecture_policy_not_authorized", assertFailsWith<ArchitecturePolicyException> { ArchitecturePolicyImporter().import(model, policy) }.code)
        val input = fixture.input(model, listOf(ALLOWED_DEPENDENCY_EDGE))
        val untrusted = input.copy(context = input.context.copy(policies = mapOf("owner-approved" to "true", "architecture" to "Clean Architecture")))
        val result = ArchitecturePolicyProvider(model).analyze(untrusted)
        assertEquals(listOf("architecture_owner_policy_not_authorized"), result.reasons)
        assertTrue(result.findings.isEmpty()); assertTrue(result.coverage.none { it.successful() })
        assertEquals(listOf("architecture_verified_project_model_required"), ArchitecturePolicyProvider().analyze(input).reasons)
        assertFailsWith<Exception> { ContractJson.decodeFromString<ArchitecturePolicyBundle>(ContractJson.encodeToString(policy).dropLast(1) + ",\"approved\":true}") }
    }

    @Test fun explicitForbiddenActualGradleEdgeReportsWithoutImportOrPackageHeuristics(): Unit {
        val fixture = ArchitectureFixtures(); val model = fixture.model(listOf(PolicyEdge(":billing", ":pricing"), PolicyEdge(":pricing", ":catalog")))
        val policy = fixture.bundle(DeclaredArchitecturePolicy.AllowedEdges(fixture.scope(), DefaultEdgePolicy.ALLOW, emptyList(), listOf(PolicyEdge("billing", "pricing"))))
        val result = fixture.analyze(model, policy, listOf(ALLOWED_DEPENDENCY_EDGE))
        val finding = result.findings.single()
        assertEquals(ALLOWED_DEPENDENCY_EDGE, finding.ruleId); assertEquals("policy", finding.kind); assertEquals("high", finding.certainty)
        assertEquals("billing/build.gradle", finding.location.path)
        assertEquals(fixture.snapshot.files.single { it.path == finding.location.path }.digest, finding.location.fileDigest)
        assertEquals("pricing/build.gradle", finding.relatedLocations.single().path)
        assertTrue(finding.evidence.any { it.kind == "edge-witness" && it.summary.contains(":billing|jvm|main") && it.summary.contains("jvmApiElements") })
        assertTrue(finding.evidence.any { it.kind == "owner-policy" && it.digest == policy.digest() })
        assertTrue(result.coverage.single().implemented); assertFalse(result.coverage.single().qualified)
        assertNull(result.coverage.single().certificateId); assertFalse(result.complete())
    }

    @Test fun explicitAllowedEdgesAndTestScopesAreNotReplacedByUniversalLayering(): Unit {
        val fixture = ArchitectureFixtures(); val model = fixture.model(listOf(PolicyEdge(":billing", ":pricing")))
        val scope = fixture.scope().copy(nodeMapping = mapOf(":billing" to "presentation", ":pricing" to "domain", ":catalog" to "data"))
        val policy = fixture.bundle(DeclaredArchitecturePolicy.AllowedEdges(scope, DefaultEdgePolicy.FORBID,
            listOf(PolicyEdge("presentation", "domain")), emptyList()))
        val result = fixture.analyze(model, policy, listOf(ALLOWED_DEPENDENCY_EDGE))
        assertTrue(result.findings.isEmpty()); assertEquals("unqualified", result.coverage.single().state)
        assertFalse(result.complete()); assertFalse(result.coverage.single().successful())
        val unselectedScope = scope.copy(compilationIds = listOf(":catalog|jvm|main"))
        val outside = fixture.analyze(model, fixture.bundle(DeclaredArchitecturePolicy.AllowedEdges(unselectedScope, DefaultEdgePolicy.FORBID, emptyList(), emptyList())), listOf(ALLOWED_DEPENDENCY_EDGE))
        assertTrue(outside.findings.isEmpty()); assertEquals("unsupported", outside.coverage.single().state)
        assertEquals("architecture_context_outside_explicit_owner_policy_scope", outside.coverage.single().reason)
    }

    @Test fun directAndTransitiveAreDifferentExplicitOwnerContracts(): Unit {
        val fixture = ArchitectureFixtures(); val model = fixture.model(listOf(PolicyEdge(":billing", ":pricing"), PolicyEdge(":pricing", ":catalog")))
        fun policy(traversal: ArchitectureEdgeTraversal) = fixture.bundle(DeclaredArchitecturePolicy.AllowedEdges(fixture.scope(traversal = traversal),
            DefaultEdgePolicy.ALLOW, emptyList(), listOf(PolicyEdge("billing", "catalog"))))
        assertTrue(fixture.analyze(model, policy(ArchitectureEdgeTraversal.DIRECT), listOf(ALLOWED_DEPENDENCY_EDGE)).findings.isEmpty())
        val transitive = fixture.analyze(model, policy(ArchitectureEdgeTraversal.TRANSITIVE), listOf(ALLOWED_DEPENDENCY_EDGE)).findings.single()
        assertTrue(transitive.diagnosis.contains("billing -> catalog"))
        val witness = transitive.evidence.single { it.kind == "edge-witness" }.summary
        assertTrue(witness.contains(":billing -> :pricing")); assertTrue(witness.contains(":pricing -> :catalog"))
    }

    @Test fun sccCycleAlgorithmProducesOneDeterministicWitnessPerComponent(): Unit {
        val fixture = ArchitectureFixtures()
        val cycle = listOf(PolicyEdge(":billing", ":pricing"), PolicyEdge(":pricing", ":catalog"), PolicyEdge(":catalog", ":billing"), PolicyEdge(":pricing", ":billing"))
        val model = fixture.model(cycle); val policy = fixture.bundle(DeclaredArchitecturePolicy.AcyclicGraph(fixture.scope()))
        val result = fixture.analyze(model, policy, listOf(DEPENDENCY_CYCLE_POLICY))
        assertEquals(1, result.findings.size)
        assertTrue(result.findings.single().diagnosis.contains("SCC billing, catalog, pricing"))
        val repeated = fixture.analyze(model, policy, listOf(DEPENDENCY_CYCLE_POLICY))
        assertEquals(result.findings.map { it.fingerprint }, repeated.findings.map { it.fingerprint })
        val allowed = fixture.bundle(DeclaredArchitecturePolicy.AcyclicGraph(fixture.scope(), listOf(listOf("billing", "catalog", "pricing"))))
        assertTrue(fixture.analyze(model, allowed, listOf(DEPENDENCY_CYCLE_POLICY)).findings.isEmpty())
        val insufficientException = fixture.bundle(DeclaredArchitecturePolicy.AcyclicGraph(fixture.scope(), listOf(listOf("billing", "pricing"))))
        assertEquals(1, fixture.analyze(model, insufficientException, listOf(DEPENDENCY_CYCLE_POLICY)).findings.size)
    }

    @Test fun dagSelfEdgeAndOwnerNodeConsolidationHaveExplicitDifferentPolicies(): Unit {
        val fixture = ArchitectureFixtures(); val dag = fixture.model(listOf(PolicyEdge(":billing", ":pricing"), PolicyEdge(":pricing", ":catalog")))
        assertTrue(fixture.analyze(dag, fixture.bundle(DeclaredArchitecturePolicy.AcyclicGraph(fixture.scope())), listOf(DEPENDENCY_CYCLE_POLICY)).findings.isEmpty())
        val consolidated = fixture.scope(self = false).copy(nodeMapping = fixture.modules.associateWith { "one-approved-cluster" })
        assertTrue(fixture.analyze(dag, fixture.bundle(DeclaredArchitecturePolicy.AcyclicGraph(consolidated)), listOf(DEPENDENCY_CYCLE_POLICY)).findings.isEmpty())
        val forbidSelf = consolidated.copy(includeMappedSelfEdges = true)
        val result = fixture.analyze(dag, fixture.bundle(DeclaredArchitecturePolicy.AcyclicGraph(forbidSelf)), listOf(DEPENDENCY_CYCLE_POLICY))
        assertEquals(1, result.findings.size); assertTrue(result.findings.single().diagnosis.contains("one-approved-cluster"))
    }

    @Test fun mergedOwnerNodesCanCreateQuotientCycleWithoutActualModuleCycle(): Unit {
        val fixture = ArchitectureFixtures()
        // Actual graph is the DAG catalog -> billing -> pricing, with no return path.
        val model = fixture.model(listOf(PolicyEdge(":catalog", ":billing"), PolicyEdge(":billing", ":pricing")))
        val scope = fixture.scope(self = false).copy(nodeMapping = mapOf(
            ":billing" to "B", ":catalog" to "A", ":pricing" to "A"))
        val result = fixture.analyze(model, fixture.bundle(DeclaredArchitecturePolicy.AcyclicGraph(scope)), listOf(DEPENDENCY_CYCLE_POLICY))
        val finding = result.findings.single()
        assertTrue(finding.diagnosis.contains("quotient graph contains SCC A, B"))
        assertTrue(finding.diagnosis.contains("not necessarily an actual-module cycle"))
        assertTrue(finding.evidence.single { it.kind == "graph-precision" }.summary.contains("different actual identities"))
        assertTrue(finding.evidence.single { it.kind == "edge-witness" }.summary.contains(":catalog -> :billing"))
        assertTrue(finding.evidence.single { it.kind == "edge-witness" }.summary.contains(":billing -> :pricing"))
        val transitive = graph(model, scope.copy(traversal = ArchitectureEdgeTraversal.TRANSITIVE))
        assertTrue(transitive.edges.any { it.from == "A" && it.to == "B" })
        assertTrue(transitive.edges.any { it.from == "B" && it.to == "A" })
    }

    @Test fun graphTraversalAndSccWitnessPollCancellationInsideAlgorithms(): Unit {
        val fixture = ArchitectureFixtures()
        val model = fixture.model(listOf(PolicyEdge(":billing", ":pricing"), PolicyEdge(":pricing", ":catalog"), PolicyEdge(":catalog", ":billing")))
        var polls = 0
        assertFailsWith<java.util.concurrent.CancellationException> {
            graph(model, fixture.scope(traversal = ArchitectureEdgeTraversal.TRANSITIVE), CancellationToken { ++polls > 12 })
        }
        assertEquals(13, polls)
        val selected = graph(model, fixture.scope())
        polls = 0
        assertFailsWith<java.util.concurrent.CancellationException> { stronglyConnected(selected, CancellationToken { ++polls > 3 }) }
        polls = 0
        assertFailsWith<java.util.concurrent.CancellationException> { cycleWitness(selected, selected.nodes, CancellationToken { ++polls > 2 }) }
        val bundle = fixture.bundle(DeclaredArchitecturePolicy.AcyclicGraph(fixture.scope()))
        polls = 0
        val cancelled = ArchitecturePolicyProvider(model, fixture.policies(model, bundle)).analyze(
            fixture.input(model, listOf(DEPENDENCY_CYCLE_POLICY)), CancellationToken { ++polls > 6 })
        assertEquals("cancelled", cancelled.status); assertTrue(cancelled.findings.isEmpty())
        assertTrue(cancelled.coverage.none { it.successful() })
    }

    @Test fun boundedGraphDoesNotAllowUnboundedFindingBatches(): Unit {
        val modules = listOf(":billing", ":pricing", ":catalog") + (1..33).map { ":fixtureModule$it" }
        val fixture = ArchitectureFixtures(modules)
        val edges = modules.flatMap { from -> modules.filter { it != from }.map { PolicyEdge(from, it) } }
        val model = fixture.model(edges)
        val policy = fixture.bundle(DeclaredArchitecturePolicy.AllowedEdges(fixture.scope(), DefaultEdgePolicy.FORBID, emptyList(), emptyList()))
        val result = fixture.analyze(model, policy, listOf(ALLOWED_DEPENDENCY_EDGE))
        assertTrue(result.findings.isEmpty())
        assertEquals("architecture_finding_count_bound_exceeded", result.coverage.single().reason)
        assertEquals("unsupported", result.coverage.single().state)
        assertFalse(result.complete()); assertFalse(result.coverage.single().successful())
    }

    @Test fun largeEscapedOwnerIdentitiesAbstainBeforeGraphSerialization(): Unit {
        val modules = listOf(":billing", ":pricing", ":catalog") + (1..33).map { ":fixtureModule$it" }
        val fixture = ArchitectureFixtures(modules)
        val edges = modules.flatMap { from -> modules.filter { it != from }.map { PolicyEdge(from, it) } }.take(500)
        val model = fixture.model(edges)
        val scope = fixture.scope().copy(nodeMapping = modules.withIndex().associate { (index, module) ->
            module to ("owner$index-" + "\\\"".repeat(1990))
        })
        val policy = fixture.bundle(DeclaredArchitecturePolicy.AllowedEdges(scope, DefaultEdgePolicy.FORBID, emptyList(), emptyList()))
        val result = fixture.analyze(model, policy, listOf(ALLOWED_DEPENDENCY_EDGE))
        assertTrue(result.findings.isEmpty())
        assertEquals("architecture_graph_text_bound_exceeded", result.coverage.single().reason)
        assertEquals("unsupported", result.coverage.single().state)
        assertFalse(result.coverage.single().successful())
    }

    @Test fun policyTextIsBoundedBeforeDetachmentOrAuthorityDigestSerialization(): Unit {
        val fixture = ArchitectureFixtures(); val model = fixture.model()
        val largeNode = "owner-" + "x".repeat(4088)
        val scope = fixture.scope().copy(nodeMapping = (1..4096).associate { ":actual$it" to largeNode })
        val policy = fixture.bundle(DeclaredArchitecturePolicy.AcyclicGraph(scope))
        assertEquals("architecture_policy_invalid", assertFailsWith<ArchitecturePolicyException> {
            ArchitecturePolicyImporter().import(model, policy)
        }.code)
    }

    @Test fun escapeSafeTextEstimateRejectsOversizeWitnessWithoutEncodingIt(): Unit {
        val escaped = "\"\\\n\u0000\u2603\uD83D\uDE00"
        val tiny = listOf(ArchitectureGraphEdge(escaped, escaped, listOf(EdgeOrigin(escaped, escaped, escaped, null))))
        assertTrue(graphTextUpperBound(tiny) >= ContractJson.encodeToString(tiny).toByteArray(Charsets.UTF_8).size)
        val largeWitness = listOf(ArchitectureGraphEdge("A", "B", (1..5000).map { EdgeOrigin("actual$it", "target$it", "compilation$it", "variant") }))
        assertEquals("architecture_witness_report_bound_exceeded", assertFailsWith<ArchitectureGraphException> {
            graphTextUpperBound(largeWitness, 512L * 1024, "architecture_witness_report_bound_exceeded")
        }.code)
    }

    @Test fun actualSourceSetRefinementEdgesAreNotGuessedFromSourceDirectoryNames(): Unit {
        val fixture = ArchitectureFixtures(); val model = fixture.model(refine = true)
        val scope = fixture.scope(kind = ArchitectureGraphKind.SOURCE_SET_REFINEMENT).copy(nodeMapping = fixture.modules.flatMap {
            listOf("$it|main" to "${it.removePrefix(":")}-platform", "$it|common" to "${it.removePrefix(":")}-shared")
        }.toMap())
        val policy = fixture.bundle(DeclaredArchitecturePolicy.AllowedEdges(scope, DefaultEdgePolicy.ALLOW, emptyList(), listOf(PolicyEdge("billing-platform", "billing-shared"))))
        val finding = fixture.analyze(model, policy, listOf(ALLOWED_DEPENDENCY_EDGE)).findings.single()
        assertTrue(finding.evidence.single { it.kind == "edge-witness" }.summary.contains(":billing|main -> :billing|common"))
        assertTrue(fixture.analyze(model, fixture.bundle(DeclaredArchitecturePolicy.AcyclicGraph(scope)), listOf(DEPENDENCY_CYCLE_POLICY)).findings.isEmpty())
    }

    @Test fun missingMembershipTruncatedGraphAndUnselectedUnknownModuleAbstain(): Unit {
        val fixture = ArchitectureFixtures(); val edges = listOf(PolicyEdge(":billing", ":pricing")); val model = fixture.model(edges)
        val scopes = listOf(fixture.scope().copy(nodeMapping = mapOf(":billing" to "billing")),
            fixture.scope().copy(compilationIds = listOf(":billing|jvm|main")),
            fixture.scope().copy(nodeMapping = fixture.scope().nodeMapping + (":unregistered" to "unknown")))
        scopes.forEach { scope ->
            val result = fixture.analyze(model, fixture.bundle(DeclaredArchitecturePolicy.AllowedEdges(scope, DefaultEdgePolicy.FORBID, emptyList(), emptyList())), listOf(ALLOWED_DEPENDENCY_EDGE))
            assertTrue(result.findings.isEmpty()); assertEquals("unsupported", result.coverage.single().state)
            assertFalse(result.coverage.single().successful())
        }
        val unknown = fixture.model(edges, listOf(ModelIssue("dependency_resolution_failed", ":catalog", "Unknown edge may close a cycle")))
        val result = fixture.analyze(unknown, fixture.bundle(DeclaredArchitecturePolicy.AcyclicGraph(fixture.scope())), listOf(DEPENDENCY_CYCLE_POLICY))
        assertTrue(result.findings.isEmpty()); assertTrue(result.reasons.single().contains("architecture_model_graph_incomplete"))
    }

    @Test fun exportedApiOwnershipAndPackageGraphsNeedActualUnavailableSemanticEvidence(): Unit {
        val fixture = ArchitectureFixtures(); val model = fixture.model()
        val bundle = fixture.bundle(
            DeclaredArchitecturePolicy.ExportedApi(fixture.scope(), "owner-jvm-api-boundary", listOf(PolicyEdge("billing", "catalog"))),
            DeclaredArchitecturePolicy.SourceSetOwnership(fixture.scope(), "owner-portability-contract", mapOf("foundation-implementation" to listOf(":billing|main"))),
            DeclaredArchitecturePolicy.AllowedEdges(fixture.scope(kind = ArchitectureGraphKind.RESOLVED_SYMBOL_DEPENDENCY), DefaultEdgePolicy.FORBID, emptyList(), emptyList()))
        val result = fixture.analyze(model, bundle, listOf(EXPORTED_API_DEPENDENCY_POLICY, SOURCE_SET_OWNERSHIP_POLICY, ALLOWED_DEPENDENCY_EDGE))
        assertTrue(result.findings.isEmpty()); assertTrue(result.coverage.all { it.state == "unsupported" && !it.implemented && !it.successful() })
        assertTrue(result.reasons.any { it.contains("effective_api_export_visibility") })
        assertTrue(result.reasons.any { it.contains("binary_symbol_ownership") })
        assertTrue(result.reasons.any { it.contains("complete_resolved_symbol_graph") })
    }

    @Test fun exactPolicyAuthorityDetachesDataRejectsReplayRetargetAndConflictingRules(): Unit {
        val fixture = ArchitectureFixtures(); val model = fixture.model()
        val mappings = fixture.scope().nodeMapping.toMutableMap()
        val bundle = fixture.bundle(DeclaredArchitecturePolicy.AllowedEdges(fixture.scope().copy(nodeMapping = mappings), DefaultEdgePolicy.ALLOW, emptyList(), emptyList()))
        val request = ArchitecturePolicyImporter.request(model, bundle)
        val importer = ArchitecturePolicyImporter(ExactArchitecturePolicyAuthority(ArchitecturePolicyPermit(request, {})))
        val authorized = importer.import(model, bundle); mappings.clear()
        assertEquals(3, authorized.bundle.policies.single().scope.nodeMapping.size)
        assertFailsWith<UnsupportedOperationException> { (authorized.bundle.policies.single().scope.nodeMapping as MutableMap<*, *>).clear() }
        assertEquals("architecture_policy_mismatch_or_replay", assertFailsWith<ArchitecturePolicyException> {
            importer.import(model, bundle.copy(policies = authorized.bundle.policies))
        }.code)
        val otherNamespace = bundle.copy(namespace = fixture.namespace.copy(lineageId = "unrelated-owner-history"), policies = authorized.bundle.policies)
        assertEquals("architecture_policy_binding_mismatch", assertFailsWith<ArchitecturePolicyException> { ArchitecturePolicyImporter().import(model, otherNamespace) }.code)
        val invalid = fixture.bundle(DeclaredArchitecturePolicy.AllowedEdges(fixture.scope(), DefaultEdgePolicy.ALLOW,
            listOf(PolicyEdge("billing", "pricing")), listOf(PolicyEdge("billing", "pricing"))))
        assertEquals("architecture_policy_invalid", assertFailsWith<ArchitecturePolicyException> { ArchitecturePolicyImporter().import(model, invalid) }.code)
    }

    @Test fun inputSnapshotContextConfigurationAndCancellationCannotBeRelabeled(): Unit {
        val fixture = ArchitectureFixtures(); val model = fixture.model(); val bundle = fixture.bundle(DeclaredArchitecturePolicy.AcyclicGraph(fixture.scope()))
        val provider = ArchitecturePolicyProvider(model, fixture.policies(model, bundle)); val input = fixture.input(model, listOf(DEPENDENCY_CYCLE_POLICY))
        val changedInputs = listOf(input.copy(snapshot = input.snapshot.copy(snapshotId = Digests.sha256("other-capture"))),
            input.copy(context = input.context.copy(configurationDigest = Digests.sha256("other-policy"))),
            input.copy(context = input.context.copy(compilerArguments = listOf("untrusted-args"))))
        changedInputs.forEach { changed -> val result = provider.analyze(changed); assertTrue(result.findings.isEmpty()); assertTrue(result.coverage.none { it.successful() }) }
        assertEquals("cancelled", provider.analyze(input, CancellationToken { true }).status)
        assertTrue(provider.analyze(input.copy(requiredRuleIds = emptyList())).coverage.isEmpty())
        assertTrue(provider.analyze(input.copy(requiredRuleIds = listOf(DEPENDENCY_CYCLE_POLICY, DEPENDENCY_CYCLE_POLICY))).reasons.single().contains("duplicate"))
    }
}

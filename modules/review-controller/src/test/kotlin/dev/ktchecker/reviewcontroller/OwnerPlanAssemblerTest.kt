package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.RequiredPlanSlot
import dev.ktchecker.projectmodel.*
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OwnerPlanAssemblerTest {
    @TempDir lateinit var temp: Path
    private fun fixture() = ControllerFixtures(temp.resolve(UUID.randomUUID().toString()))
    private fun assertCode(code: String, action: () -> Unit) = assertEquals(code, assertFailsWith<ReviewControllerException>(block = action).code)
    private fun slot(assembly: AssembledReviewPlan, id: String?): RequiredPlanSlot {
        val slotId = assembly.provenance.slots.single { it.compilationId == id }.slotId
        return assembly.plan.slots.single { it.slotId == slotId }
    }

    @Test fun initialPlanIncludesEveryContextAndFullCatalogWithWideningSeparateFromPendingReasons() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        val assembly = prep.assembler(f).assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId))
        assertEquals(model.compilationIds.toSet(), assembly.provenance.slots.map { it.compilationId }.toSet())
        assertEquals(assembly.plan.slots.map { it.slotId }.sorted(), assembly.plan.slots.map { it.slotId })
        assertEquals(digest(model.snapshot), assembly.plan.sourceBindingDigest)
        assertEquals(digest(model.export.generatedInputs), assembly.plan.generatedInputsDigest)
        assertEquals(model.ingressCertificate, assembly.plan.modelCertificateId)
        assertEquals(assembly.provenance.impact.certificateId, assembly.plan.impactCertificateId)
        assertContains(assembly.provenance.impact.reasons, "initial_model_requires_all_consumers")
        assertSame(model, assembly.provenance.after)
        for (row in assembly.plan.slots) {
            assertEquals("pending", row.state); assertTrue(row.reasons.isEmpty()); assertNotNull(row.identity)
            assertEquals(f.inventory.ruleIds, row.input.requiredRuleIds)
            assertEquals(model.snapshot, row.input.snapshot); assertEquals(capture.root.toString(), row.input.materializedRoot)
            assertTrue(row.input.context.fullProject)
            assertEquals(exactPlanContextIdentity(row.input), row.identity)
            assertTrue(PLAN_ACTIVATION_POLICY_KEY in row.input.context.policies)
            assertTrue(PLAN_EXECUTION_POLICY_KEY in row.input.context.policies)
            assertEquals(f.inventory.catalogDigest, row.input.context.policies[CATALOG_POLICY_KEY])
            assertTrue(f.binding() to row.input.analysisKey() in prep.policyRecords)
            assertTrue(f.binding() to row.input.analysisKey() in prep.executionRecords)
            assertNull(row.input.execution) // A profile's approved flag is not a provider capability.
        }
        assertEquals(model.compilationIds.size, prep.policyRequests.size)
        assertEquals(model.compilationIds.size, prep.executionRequests.size)
        assertTrue(capture.verifies >= 3)
    }

    @Test fun impactIsDerivedFromLiveBeforeAfterButUnaffectedContextsAreStillRechecked() {
        for (changed in listOf("lib/src/commonMain/kotlin/Common.kt", "lib/src/commonMain/resources/message.txt")) {
            val f = fixture(); val old = f.capture(); val before = f.import(old)
            val next = f.capture(f.files().toMutableMap().apply { this[changed] = getValue(changed) + "\nunit change\n" })
            val after = f.import(next); val prep = TestPreparations()
            val assembly = prep.assembler(f).assemble(f.binding(), f.request(), before, after, next.materialization(after), listOf(f.binding().clientId))
            assertEquals(listOf(changed), assembly.provenance.impact.changedFiles)
            assertFalse(assembly.provenance.impact.obligations.any { it.compilationId == ":independent|jvm|main" })
            assertEquals(after.compilationIds.toSet(), assembly.provenance.slots.map { it.compilationId }.toSet())
            assertContains(assembly.provenance.slots.single { it.compilationId == ":independent|jvm|main" }.explanations,
                "unaffected_context_rechecked_without_reuse_proof")
            assembly.plan.slots.forEach { assertEquals(listOf(changed), it.input.changedPaths); assertEquals(before.snapshot.snapshotId, it.input.baselineSnapshotId) }
        }
    }

    @Test fun orderedArgumentsClasspathAndMembershipArePreservedAndFinalPolicyExecutionAddendaAreHashed() {
        val f = fixture(); val capture = f.capture(); val graph = f.graph(capture); val id = ":lib|jvm|main"
        val before = f.import(capture, graph)
        val nextGraph = graph.copy(compilations = graph.compilations.map {
            if (it.id == id) it.copy(classpath = it.classpath.reversed(), sourceFiles = it.sourceFiles.reversed(),
                compilerArguments = listOf("-api-version=2.4", "-language-version", "2.4", "-module-name", "actual_module", "-progressive")) else it
        })
        val after = f.import(capture, nextGraph); val prep = TestPreparations()
        val assembly = prep.assembler(f).assemble(f.binding(), f.request(), before, after, capture.materialization(after), listOf(f.binding().clientId))
        val input = slot(assembly, id).input; val identity = slot(assembly, id).identity!!; val actual = after.compilation(id)!!
        assertEquals(actual.compilerArguments, input.context.compilerArguments)
        assertEquals(actual.classpath.map { ArtifactIdentity(it.coordinate, it.digest!!, it.path) }, input.context.classpath)
        assertEquals(actual.sourceFiles.map { it.path }, input.context.compilationFiles)
        assertEquals(digest(input.context.compilerArguments), identity.compilerArgumentsDigest)
        assertEquals(digest(input.context.classpath), identity.classpathDigest)
        assertEquals(digest(actual.sourceFiles.map { SnapshotFile(it.path, it.digest, it.size) }), identity.compilationFilesDigest)
        assertEquals(digest(input.context.policies), identity.policiesDigest)
        assertNotEquals(digest(after.context(id, f.configuration).context!!.policies), identity.policiesDigest)
        assertNotEquals(digest(input.context.classpath.reversed()), identity.classpathDigest)
        assertContains(assembly.provenance.impact.reasons, "model_topology_membership_or_arguments_changed")
        assertTrue(assembly.plan.slots.all { it.reasons.isEmpty() })
    }

    @Test fun wholeGeneratedMembershipPropertiesAndRootsAreBoundFromTheVerifiedModel() {
        val f = fixture(); val capture = f.capture(); val graph = f.graph(capture)
        val output = graph.sourceSets.single { it.id == ":lib|commonMain" }.sources.single()
        val generated = GeneratedSourceInput(":generateUnit", listOf(":lib|commonMain"), listOf(output), listOf(output),
            listOf("lib/src/commonMain/kotlin"), "captured", mapOf("mode" to "\"one\""))
        val before = f.import(capture, graph.copy(generatedInputs = listOf(generated)))
        val after = f.import(capture, graph.copy(generatedInputs = listOf(generated.copy(inputProperties = mapOf("mode" to "\"two\"")))))
        val prep = TestPreparations()
        val a = prep.assembler(f).assemble(f.binding(), f.request("a"), null, before, capture.materialization(before), listOf(f.binding().clientId))
        val b = prep.assembler(f).assemble(f.binding(), f.request("b"), before, after, capture.materialization(after), listOf(f.binding().clientId))
        assertNotEquals(a.plan.generatedInputsDigest, b.plan.generatedInputsDigest)
        assertEquals(digest(after.export.generatedInputs), b.plan.generatedInputsDigest)
        assertEquals(a.plan.sourceBindingDigest, b.plan.sourceBindingDigest)
        assertContains(b.provenance.impact.reasons, "model_topology_membership_or_arguments_changed")
    }

    @Test fun removedCompilationIsAnUnsupportedSlotOnTheNewSnapshotNotAnOmittedOrExecutedOldInput() {
        val f = fixture(); val old = f.capture(); val before = f.import(old)
        val next = f.capture(f.files() - "lib/src/desktopMain/kotlin/Desktop.kt")
        val graph = f.graph(next).let { it.copy(compilations = it.compilations.filterNot { c -> c.id == ":lib|desktop|main" },
            sourceSets = it.sourceSets.filterNot { s -> s.id == ":lib|desktopMain" }) }
        val after = f.import(next, graph); assertTrue(after.issues.isEmpty())
        val prep = TestPreparations()
        val assembly = prep.assembler(f).assemble(f.binding(), f.request(), before, after, next.materialization(after), listOf(f.binding().clientId))
        assertEquals(before.compilationIds.toSet(), assembly.provenance.slots.map { it.compilationId }.toSet())
        val removed = slot(assembly, ":lib|desktop|main")
        assertEquals("unsupported", removed.state); assertNull(removed.identity)
        assertContains(removed.reasons, "consumer_removed_without_retirement_evidence")
        assertEquals("removed_unresolved", assembly.provenance.slots.single { it.compilationId == ":lib|desktop|main" }.impactObligation!!.state)
        assertEquals(after.snapshot, removed.input.snapshot); assertEquals(next.root.toString(), removed.input.materializedRoot)
        assertTrue(removed.input.context.classpath.isEmpty()); assertEquals(emptyList(), removed.input.context.compilationFiles)
        assertFalse(removed.input.context.fullProject); assertEquals(f.inventory.ruleIds, removed.input.requiredRuleIds)
        assertFalse(prep.executionRequests.any { it.compilationId == ":lib|desktop|main" })
        assertTrue(assembly.plan.slots.filterNot { it.slotId == removed.slotId }.all { it.state == "pending" })
    }

    @Test fun unchangedCompilationIdCannotSilentlyRetireItsPreviousActualRuleContext() {
        val f = fixture(); val capture = f.capture(); val graph = f.graph(capture); val id = ":lib|jvm|main"
        val before = f.import(capture, graph)
        val after = f.import(capture, graph.copy(compilations = graph.compilations.map {
            if (it.id == id) it.copy(defaultSourceSetId = ":lib|commonMain") else it
        }))
        assertTrue(after.issues.isEmpty())
        val prep = TestPreparations()
        val assembly = prep.assembler(f).assemble(f.binding(), f.request(), before, after, capture.materialization(after), listOf(f.binding().clientId))
        assertEquals(after.compilationIds.size + 1, assembly.plan.slots.size)
        val contexts = assembly.provenance.slots.filter { it.compilationId == id }
        assertEquals(2, contexts.size)
        val previousEvidence = contexts.single { it.sourceModel === before }
        val currentEvidence = contexts.single { it.sourceModel === after }
        val previous = assembly.plan.slots.single { it.slotId == previousEvidence.slotId }
        val current = assembly.plan.slots.single { it.slotId == currentEvidence.slotId }
        assertEquals(before.context(id, f.configuration).context!!.scope, previous.input.context.scope)
        assertEquals(after.context(id, f.configuration).context!!.scope, current.input.context.scope)
        assertEquals("unsupported", previous.state); assertNull(previous.identity)
        assertContains(previous.reasons, "consumer_context_changed_without_retirement_evidence")
        assertNull(previousEvidence.impactObligation) // The planner's current obligation does not describe this old context.
        assertEquals(after.snapshot, previous.input.snapshot)
        assertEquals(emptyList(), previous.input.context.compilationFiles); assertTrue(previous.input.context.classpath.isEmpty())
        assertFalse(previous.input.context.fullProject); assertEquals(f.inventory.ruleIds, previous.input.requiredRuleIds)
        assertEquals("pending", current.state); assertTrue(current.reasons.isEmpty()); assertNotNull(current.identity)
        assertEquals(after.compilationIds.size, prep.executionRequests.size)
        assertFalse(prep.executionRequests.any { it.input.context.scope == previous.input.context.scope })
        assertEquals(assembly.plan.slots.size, assembly.plan.slots.map { it.slotId }.distinct().size)
    }

    @Test fun emptyAndIncompleteModelsKeepNonemptyUnavailableCoverageWithoutPromotingFullProject() {
        val f = fixture(); val capture = f.capture(); val graph = f.graph(capture)
        val empty = f.import(capture, graph.copy(sourceSets = emptyList(), compilations = emptyList()))
        val prep = TestPreparations()
        val assembly = prep.assembler(f).assemble(f.binding(), f.request("empty"), null, empty, capture.materialization(empty), listOf(f.binding().clientId))
        val unavailable = assembly.plan.slots.single()
        assertEquals("unsupported", unavailable.state); assertNull(unavailable.identity)
        assertContains(unavailable.reasons, "compilation_model_empty")
        assertEquals("unavailable", unavailable.input.context.scope.compilation)
        assertEquals(f.inventory.ruleIds, unavailable.input.requiredRuleIds); assertFalse(unavailable.input.context.fullProject)
        assertTrue(prep.executionRequests.isEmpty())
        val incomplete = f.import(capture, graph.copy(issues = listOf(ModelIssue("unknown_owner_input", null, "Unit unmodeled input"))))
        val next = prep.assembler(f).assemble(f.binding(), f.request("incomplete"), null, incomplete, capture.materialization(incomplete), listOf(f.binding().clientId))
        assertEquals(incomplete.compilationIds.size, next.plan.slots.size)
        next.plan.slots.forEach { assertEquals("unsupported", it.state); assertFalse(it.input.context.fullProject); assertNull(it.identity) }
    }

    @Test fun absentOwnerPreparersDoNotGrantExecutionOrInferProviderSupport() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture)
        val denied = OwnerPlanAssembler(f.inventory).assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId))
        denied.plan.slots.forEach {
            assertEquals("unsupported", it.state); assertContains(it.reasons, "captured_policy_unavailable")
            assertNull(it.identity); assertEquals(f.inventory.ruleIds, it.input.requiredRuleIds)
        }
        val prep = TestPreparations()
        val noExecution = OwnerPlanAssembler(f.inventory, OwnerPolicyPreparer(prep::policy)).assemble(f.binding(), f.request("no-execution"),
            null, model, capture.materialization(model), listOf(f.binding().clientId))
        noExecution.plan.slots.forEach { assertContains(it.reasons, "execution_preparation_unavailable"); assertEquals("unsupported", it.state) }
    }

    @Test fun unknownCompilerArgumentsAndAuxiliaryFriendPluginInputsAreUnsupportedWithoutGuessingRewrites() {
        val f = fixture(); val capture = f.capture(); val graph = f.graph(capture); val target = ":lib|jvm|main"
        val changes: List<Pair<String, (ModelCompilation) -> ModelCompilation>> = listOf(
            "friend_artifact_rewrite_unavailable" to { c: ModelCompilation -> c.copy(friendArtifacts = listOf(f.artifacts.first())) },
            "auxiliary_artifact_rewrite_unavailable" to { c: ModelCompilation -> c.copy(auxiliaryArtifacts = listOf(f.artifacts.first())) },
            "auxiliary_input_rewrite_unavailable" to { c: ModelCompilation -> c.copy(auxiliaryInputFiles = listOf(c.sourceFiles.first())) },
            "compiler_plugin_rewrite_unavailable" to { c: ModelCompilation -> c.copy(toolchain = c.toolchain!!.copy(compilerPlugins = listOf(f.artifacts.first()))) },
        ) + listOf(listOf("-Xplugin=/mutable/plugin.jar"), listOf("-P", "plugin:unit:path=/mutable/file"), listOf("@args.txt"),
            listOf("-classpath", "/mutable/classes"), listOf("source.kt"), listOf("-unknown-safe-looking-flag"), listOf("-module-name")).map { args ->
            "compiler_arguments_require_verified_rewrite" to { c: ModelCompilation -> c.copy(compilerArguments = args) }
        }
        for ((reason, change) in changes) {
            val model = f.import(capture, graph.copy(compilations = graph.compilations.map { if (it.id == target) change(it) else it }))
            assertTrue(model.issues.isEmpty())
            val prep = TestPreparations()
            val assembly = prep.assembler(f).assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId))
            val row = slot(assembly, target)
            assertEquals("unsupported", row.state); assertContains(row.reasons, reason); assertNull(row.identity)
            assertEquals(model.compilation(target)!!.compilerArguments, row.input.context.compilerArguments)
            assertEquals(f.inventory.ruleIds, row.input.requiredRuleIds)
            assertFalse(prep.executionRequests.any { it.compilationId == target })
            assertTrue(assembly.plan.slots.filterNot { it.slotId == row.slotId }.all { it.state == "pending" })
        }
    }

    @Test fun policyCallbackCannotRetargetNarrowOrReorderAnyProtectedInput() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture)
        val mutations: List<(AnalysisInput) -> AnalysisInput> = listOf(
            { it.copy(requiredRuleIds = it.requiredRuleIds.drop(1)) },
            { it.copy(requiredRuleIds = it.requiredRuleIds.reversed()) },
            { it.copy(changedPaths = listOf("invented.kt")) },
            { it.copy(baselineSnapshotId = Digests.sha256("unverified-baseline")) },
            { it.copy(materializedRoot = f.work.resolve("alias-root").toString()) },
            { it.copy(snapshot = it.snapshot.copy(declaredRoots = listOf("another-root"))) },
            { it.copy(context = it.context.copy(scope = it.context.scope.copy(module = ":other"))) },
            { it.copy(context = it.context.copy(fullProject = false)) },
            { it.copy(context = it.context.copy(compilationFiles = emptyList())) },
            { it.copy(context = it.context.copy(compilerArguments = it.context.compilerArguments.reversed())) },
            { it.copy(context = it.context.copy(classpath = it.context.classpath.reversed())) },
            { it.copy(context = it.context.copy(modelDigest = Digests.sha256("other-model"))) },
            { it.copy(context = it.context.copy(dependencyDigest = Digests.sha256("other-dependency"))) },
            { it.copy(context = it.context.copy(toolchainDigest = Digests.sha256("other-toolchain"))) },
            { it.copy(context = it.context.copy(configurationDigest = Digests.sha256("other-config"))) },
            { it.copy(context = it.context.copy(compilerVersion = "different")) },
            { it.copy(context = it.context.copy(libraries = mapOf("unverified" to "library"))) },
            { it.copy(context = it.context.copy(policies = it.context.policies + ("ktchecker.model" to "caller-declared"))) },
            { it.copy(context = it.context.copy(policies = it.context.policies + ("unsanctioned-policy" to "claim"))) },
            { it.copy(execution = TrustedExecution("/unapproved", approved = true)) },
            { it.copy(outputRoot = f.work.resolve("output").toString()) },
        )
        mutations.forEach { mutate ->
            val prep = TestPreparations()
            val assembler = OwnerPlanAssembler(f.inventory, OwnerPolicyPreparer { request ->
                val response = prep.policy(request)
                CapturedPlanPolicy(request, response.identity, mutate(response.input), response.retainForAdmission)
            }, OwnerExecutionPreparer(prep::execution))
            assertCode("policy_input_mutation") { assembler.assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId)) }
        }
    }

    @Test fun activationMustAccountForAllRulesEvenWhenEveryRuleIsDisabled() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture)
        val mutations: List<(PlanActivation) -> PlanActivation> = listOf(
            { it.copy(decisions = it.decisions.drop(1)) }, { it.copy(decisions = it.decisions + it.decisions.first()) },
            { it.copy(decisions = it.decisions + PlanRuleActivation("invented/rule", false, "unit", "unknown")) },
            { it.copy(policyDigest = Digests.sha256("unbound-policy")) }, { it.copy(schemaVersion = 2) },
        )
        for (mutate in mutations) {
            val prep = TestPreparations()
            val assembler = OwnerPlanAssembler(f.inventory, OwnerPolicyPreparer { request ->
                val response = prep.policy(request)
                val activation = ContractJson.decodeFromString<PlanActivation>(response.input.context.policies.getValue(PLAN_ACTIVATION_POLICY_KEY))
                CapturedPlanPolicy(request, response.identity, request.withActivation(mutate(activation)), response.retainForAdmission)
            }, OwnerExecutionPreparer(prep::execution))
            assertCode("activation_inventory_mismatch") { assembler.assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId)) }
        }
    }

    @Test fun preparationCallbackAliasesAndForeignClientConfigurationIdentitiesAreRejected() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        var priorPolicy: CapturedPlanPolicy? = null
        val aliasPolicy = OwnerPlanAssembler(f.inventory, OwnerPolicyPreparer { request -> priorPolicy ?: prep.policy(request).also { priorPolicy = it } },
            OwnerExecutionPreparer(prep::execution))
        assertCode("policy_preparation_binding_mismatch") { aliasPolicy.assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId)) }
        for (foreign in listOf(f.binding("other"), f.binding().copy(sessionId = "other-session"), f.binding().copy(generation = 2))) {
            val assembler = OwnerPlanAssembler(f.inventory, OwnerPolicyPreparer { request ->
                val response = prep.policy(request)
                CapturedPlanPolicy(request, response.identity.copy(binding = foreign), response.input, response.retainForAdmission)
            })
            assertCode("policy_preparation_binding_mismatch") { assembler.assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId)) }
        }
        val wrongConfig = OwnerPlanAssembler(f.inventory, OwnerPolicyPreparer { request ->
            val response = prep.policy(request)
            CapturedPlanPolicy(request, response.identity.copy(configurationDigest = Digests.sha256("other-capture")), response.input, response.retainForAdmission)
        })
        assertCode("policy_preparation_binding_mismatch") { wrongConfig.assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId)) }
        var priorExecution: CapturedPlanExecution? = null
        val aliasExecution = OwnerPlanAssembler(f.inventory, OwnerPolicyPreparer(prep::policy), OwnerExecutionPreparer { request ->
            priorExecution ?: prep.execution(request).also { priorExecution = it }
        })
        assertCode("execution_preparation_binding_mismatch") { aliasExecution.assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId)) }
    }

    @Test fun executionCallbacksCannotNarrowInputsRetargetToolchainsOrInjectProcessSettings() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture)
        val mutations: List<(AnalysisInput) -> AnalysisInput> = listOf(
            { it.copy(requiredRuleIds = emptyList()) }, { it.copy(context = it.context.copy(compilationFiles = emptyList())) },
            { it.copy(context = it.context.copy(compilerArguments = it.context.compilerArguments.reversed())) },
            { it.copy(context = it.context.copy(classpath = it.context.classpath.reversed())) },
            { it.copy(context = it.context.copy(configurationDigest = Digests.sha256("other"))) },
            { it.copy(execution = TrustedExecution("/unapproved", approved = true)) },
        )
        for (mutate in mutations) {
            val prep = TestPreparations()
            val assembler = OwnerPlanAssembler(f.inventory, OwnerPolicyPreparer(prep::policy), OwnerExecutionPreparer { request ->
                val response = prep.execution(request)
                CapturedPlanExecution(request, response.identity, response.profiles, mutate(response.input), response.retainForAdmission)
            })
            assertCode("execution_input_mutation") { assembler.assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId)) }
        }
        val prep = TestPreparations()
        val identities: List<(CapturedExecutionIdentity) -> CapturedExecutionIdentity> = listOf(
            { it.copy(binding = f.binding("another")) }, { it.copy(inputKey = Digests.sha256("other-input")) }, { it.copy(toolchainDigest = Digests.sha256("other-toolchain")) },
        )
        identities.forEach { mutate ->
            val assembler = OwnerPlanAssembler(f.inventory, OwnerPolicyPreparer(prep::policy), OwnerExecutionPreparer { request ->
                val response = prep.execution(request)
                CapturedPlanExecution(request, mutate(response.identity), response.profiles, response.input, response.retainForAdmission)
            })
            assertCode("execution_preparation_binding_mismatch") { assembler.assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId)) }
        }
    }

    @Test fun sourceNamespaceMaterializationAndAncestryMustMatchTheExactLiveCapabilities() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        for (binding in listOf(f.binding().copy(ownerId = "other"), f.binding().copy(workspaceId = "other"),
            f.binding().copy(worktreeId = "other"), f.binding().copy(lineageId = "other"))) {
            assertCode("model_namespace_mismatch") { prep.assembler(f).assemble(binding, f.request(), null, model, capture.materialization(model), listOf(binding.clientId)) }
        }
        assertEquals(0, capture.verifies); assertTrue(prep.policyRequests.isEmpty())
        val foreignFixture = ControllerFixtures(temp.resolve("foreign"), f.namespace.copy(lineageId = "foreign"))
        val foreignCapture = foreignFixture.capture(); val foreign = foreignFixture.import(foreignCapture)
        assertCode("model_namespace_mismatch") { prep.assembler(f).assemble(f.binding(), f.request(), foreign, model, capture.materialization(model), listOf(f.binding().clientId)) }
        val sameNamespace = ControllerFixtures(temp.resolve("unrelated"), f.namespace)
        val unrelatedCapture = sameNamespace.capture(sameNamespace.files() + ("unrelated.txt" to "unrelated genesis"))
        val unrelated = sameNamespace.import(unrelatedCapture)
        assertCode("model_ancestry_unavailable") { prep.assembler(f).assemble(f.binding(), f.request(), unrelated, model, capture.materialization(model), listOf(f.binding().clientId)) }
        val anotherModel = f.import(capture)
        assertCode("materialization_model_mismatch") { prep.assembler(f).assemble(f.binding(), f.request(), null, model, capture.materialization(anotherModel), listOf(f.binding().clientId)) }
        for (materialization in listOf(
            capture.materialization(model) { MaterializationVerification(it.snapshot.copy(declaredRoots = listOf("another-root")), it.materializedRoot, it.authorityDigest) },
            capture.materialization(model) { MaterializationVerification(it.snapshot, f.work.resolve("another-root").toString(), it.authorityDigest) },
        )) assertCode("materialization_binding_mismatch") { prep.assembler(f).assemble(f.binding(), f.request(), null, model, materialization, listOf(f.binding().clientId)) }
        assertTrue(prep.policyRequests.isEmpty())
    }

    @Test fun changesToSourcePolicyOrExecutionAuthorityDuringPreparationRejectTheEntireAssembly() {
        for (kind in listOf("source-capture", "source-retain", "policy", "execution")) {
            val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
            when (kind) {
                "source-capture" -> prep.afterPolicyCapture = { capture.epoch = Digests.sha256("changed-source-authority") }
                "source-retain" -> prep.afterPolicyRetain = { capture.epoch = Digests.sha256("changed-source-authority") }
                "policy" -> prep.afterPolicyRetain = { prep.policyRevision = Digests.sha256("changed-policy-authority") }
                "execution" -> prep.afterExecutionRetain = { prep.executionApproval = Digests.sha256("changed-execution-authority") }
            }
            val expected = when (kind) { "policy" -> "policy_authority_changed"; "execution" -> "execution_authority_changed"; else -> "materialization_authority_changed" }
            assertCode(expected) { prep.assembler(f).assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId)) }
        }
    }

    @Test fun recipientInventoryAndWholePlanLimitsDoNotSilentlyNarrowOrPrepareAPrefix() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        for (recipients in listOf(emptyList(), listOf(f.binding().clientId, f.binding().clientId))) {
            assertCode("plan_recipients_invalid") { prep.assembler(f).assemble(f.binding(), f.request(), null, model, capture.materialization(model), recipients) }
        }
        for (recipients in listOf(listOf(f.binding().clientId, "other"), listOf("other"))) {
            assertCode("cross_client_routing_unavailable") { prep.assembler(f).assemble(f.binding(), f.request(), null, model, capture.materialization(model), recipients) }
        }
        assertEquals(0, capture.verifies); assertTrue(prep.policyRequests.isEmpty())
        assertCode("catalog_inventory_invalid") { OwnerCatalogInventory(f.inventory.catalogDigest, emptyList()) }
        assertCode("catalog_inventory_invalid") { OwnerCatalogInventory(f.inventory.catalogDigest, listOf("unit/a", "unit/a")) }
        assertCode("plan_metadata_limit") { OwnerPlanAssembler(f.inventory, OwnerPolicyPreparer(prep::policy), OwnerExecutionPreparer(prep::execution), maxPlanBytes = 32)
            .assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId)) }
        assertTrue(prep.policyRequests.isEmpty())
        val graph = f.graph(capture)
        val prototype = graph.compilations.first { it.id == ":lib|jvm|main" }
        val excessive = f.import(capture, graph.copy(compilations = graph.compilations + (0 until 513).map {
            prototype.copy(id = ":lib|extra$it|main", target = "extra$it", compileTaskPath = ":lib:compile-extra$it")
        }))
        assertCode("plan_slot_limit") { prep.assembler(f).assemble(f.binding(), f.request(), null, excessive, capture.materialization(excessive), listOf(f.binding().clientId)) }
        assertTrue(prep.policyRequests.isEmpty())
        val retargetedAtLimit = f.import(capture, graph.copy(compilations = graph.compilations.map {
            if (it.id == prototype.id) it.copy(defaultSourceSetId = ":lib|commonMain") else it
        } + (0 until 507).map { prototype.copy(id = ":lib|extra$it|main", target = "extra$it", compileTaskPath = ":lib:compile-extra$it") }))
        assertEquals(512, retargetedAtLimit.compilationIds.size)
        assertCode("plan_slot_limit") { prep.assembler(f).assemble(f.binding(), f.request(), model, retargetedAtLimit,
            capture.materialization(retargetedAtLimit), listOf(f.binding().clientId)) }
        assertTrue(prep.policyRequests.isEmpty()) // The additional unresolved old context counts toward the complete vector.
    }
}

package dev.ktchecker.cli

import dev.ktchecker.compiler.*
import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.reviewcontroller.*
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*

/** Typed owner composition tests; every exporter receipt/compiler/JDK input is SYNTHETIC. */
class ModelPlansTest {
    @Test fun fullCatalogAndAllContextsRemainRequiredAcrossIncrementalSharedApiChange() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val previous = f.capture()
            val before = f.import(previous, intent)
            val changed = "lib/src/main/kotlin/Api.kt"
            val capture = f.capture(f.files() + (changed to "fun apiValue() = 42\n"))
            val after = f.import(capture, intent)
            val prepared = c.plans.prepare(intent, before, after, capture)
            assertEquals(after.compilationIds.size, prepared.assembly.plan.slots.size)
            assertEquals(setOf(":lib", ":app"), prepared.assembly.plan.slots.map { it.input.context.scope.module }.toSet())
            assertEquals(after.compilationIds.toSet(), prepared.assembly.provenance.slots.map { it.compilationId }.toSet())
            assertEquals(listOf(changed), prepared.assembly.provenance.impact.changedFiles)
            assertTrue(prepared.preparationReasons.isEmpty())
            prepared.assembly.plan.slots.forEach { slot ->
                assertEquals("pending", slot.state)
                assertEquals(f.catalog.ids.sorted(), slot.input.requiredRuleIds)
                assertEquals(after.snapshot, slot.input.snapshot)
                assertEquals(before.snapshot.snapshotId, slot.input.baselineSnapshotId)
                assertEquals(listOf(changed), slot.input.changedPaths)
                assertTrue(slot.input.snapshot.files.size > slot.input.context.compilationFiles!!.size)
                val evidence = assertNotNull(c.plans.evidence(slot.input))
                assertEquals(f.binding(), evidence.binding)
                assertEquals(listOf(f.rule), evidence.compilerRuleIds)
                assertEquals(setOf(c.compiler!!.id), evidence.profiles.keys)
                assertNotNull(evidence.compilerBindingDigest)
                assertNotNull(evidence.approvalDigest)
                assertEquals(intent.configuration.revisionToken, c.plans.policy(f.binding(), slot.input)!!.revisionToken)
                assertNotEquals(f.installation.digest, slot.input.context.toolchainDigest,
                    "Imported model and installed runtime identities must never be conflated")
                val activation = validatedActivation(slot.input, f.catalog)
                assertEquals(f.catalog.ids, activation.keys)
                assertEquals(setOf(f.rule), activation.values.filter { it.active }.map { it.ruleId }.toSet())
                f.catalog.records.filter { it.getValue("default_activation").jsonPrimitive.content == "excluded" }.forEach {
                    assertEquals("justified_catalog_exclusion", activation.getValue(it.getValue("id").jsonPrimitive.content).reason)
                }
            }
            assertEquals(prepared.assembly.plan, c.plans.resolve(f.binding(), f.request()))
            assertEquals(0, c.attempts.activeCount(), "Preparing/admitting a whole plan must not start a process attempt")
        }
    }

    @Test fun beforeImportPolicyCaptureCannotBeReplacedBySuppressionOrRevisionChangesDuringPreparation() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            f.setEnabled(false)
            val latest = f.mutate(ConfigAction.AddSuppression(SuppressionSpec("accepted-later", PolicySelector.Rule(f.warningRule),
                SuppressionScope.Project, SuppressionReason.ACCEPTED_RISK, "Explicit unit owner action after capture")))
            assertNotEquals(intent.configuration.revisionToken, latest.revisionToken)
            val model = f.import(capture, intent)
            val prepared = c.plans.prepare(intent, null, model, capture)
            prepared.assembly.plan.slots.forEach { slot ->
                val captured = assertNotNull(c.plans.policy(f.binding(), slot.input))
                assertEquals(intent.configuration.revisionToken, captured.revisionToken)
                assertTrue(captured.ruleSettings.getValue(f.rule).enabled)
                assertTrue(captured.suppressions.isEmpty())
                assertTrue(validatedActivation(slot.input, f.catalog).getValue(f.rule).active)
            }
            val fresh = c.plans.begin(f.binding(), f.request("fresh-policy"), true)
            assertEquals("model_captured_policy_mismatch", assertFailsWith<CliProblem> {
                c.plans.prepare(fresh, null, model, capture)
            }.code)
            assertEquals(latest.revisionToken, f.configs.capture(modelConfigBinding(f.binding())).revisionToken,
                "Review/preparation is read-only, even when the captured policy no longer matches latest")
        }
    }

    @Test fun configurationAbaKeepsTheCapturedRevisionInsteadOfAcceptingEqualPolicyContentAsAuthority() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), true)
            f.setEnabled(false)
            val latest = f.setEnabled(true)
            assertNotEquals(intent.configuration.revisionToken, latest.revisionToken)
            val capture = f.capture()
            val model = f.import(capture, intent)
            val prepared = c.plans.prepare(intent, null, model, capture)
            prepared.assembly.plan.slots.forEach { slot ->
                assertEquals(intent.configuration.revisionToken, c.plans.policy(f.binding(), slot.input)!!.revisionToken)
                assertNotEquals(latest.revisionToken, c.plans.evidence(slot.input)!!.configuration.revisionToken)
            }
            assertSame(intent, c.plans.begin(f.binding(), f.request(), true))
            assertEquals("model_intent_conflict", assertFailsWith<CliProblem> { c.plans.begin(f.binding(), f.request(), false) }.code)
        }
    }

    @Test fun absentApprovalInstallationOrActivationCannotEraseRequiredUnsupportedSlots() {
        for (reason in listOf("model_execution_not_authorized", "model_compiler_installation_unavailable", "model_compiler_rules_inactive")) {
            OwnerModelFixtures().use { f ->
                if (reason != "model_compiler_rules_inactive") f.setEnabled()
                val c = f.composition(selected = if (reason == "model_compiler_installation_unavailable") null else f.installation)
                val intent = c.plans.begin(f.binding(), f.request(), reason != "model_execution_not_authorized")
                val capture = f.capture()
                val model = f.import(capture, intent)
                val prepared = c.plans.prepare(intent, null, model, capture)
                assertEquals(model.compilationIds.toSet(), prepared.preparationReasons.keys)
                assertEquals(setOf(reason), prepared.preparationReasons.values.toSet())
                prepared.assembly.plan.slots.forEach { slot ->
                    assertEquals("unsupported", slot.state)
                    assertEquals(f.catalog.ids.sorted(), slot.input.requiredRuleIds)
                    assertContains(slot.reasons, "execution_preparation_unavailable")
                    assertTrue(c.plans.evidence(slot.input)!!.profiles.isEmpty())
                    assertNotNull(c.plans.policy(f.binding(), slot.input))
                    val archive = f.archive(slot.input)
                    assertEquals("model_live_authority_unavailable", assertFailsWith<KernelException> {
                        c.plans.observe(slot.input, archive.effective, archive::verify)
                    }.code)
                }
                assertEquals(0, c.attempts.activeCount())
            }
        }
    }

    @Test fun nativePluginAndUnmodeledArgumentGapsStayVisibleBesideExecutableJvmContexts() {
        for (gap in listOf("native", "plugin", "arguments")) OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val graph = f.graph(capture).let { graph -> graph.copy(compilations = graph.compilations.map { compilation ->
                if (compilation.moduleId != ":lib") compilation else when (gap) {
                    "native" -> compilation.copy(target = "iosArm64", platform = "native", toolchain = compilation.toolchain!!.copy(kind = "native"))
                    "plugin" -> compilation.copy(toolchain = compilation.toolchain!!.copy(compilerPlugins = listOf(f.compilerArtifacts.first())))
                    else -> compilation.copy(compilerArguments = compilation.compilerArguments + "-Xsam-conversions=class")
                }
            }) }
            val model = f.import(capture, intent, graph)
            val plan = c.plans.prepare(intent, null, model, capture)
            val missing = plan.assembly.plan.slots.single { it.input.context.scope.module == ":lib" }
            assertEquals("unsupported", missing.state)
            assertEquals(f.catalog.ids.sorted(), missing.input.requiredRuleIds)
            assertTrue(c.plans.evidence(missing.input)!!.profiles.isEmpty())
            if (gap == "native") {
                assertEquals("iosArm64", missing.input.context.scope.target)
                assertEquals("compiler_model_platform_unsupported", plan.preparationReasons.getValue(":lib|ownerJvm|main"))
            } else assertContains(missing.reasons, if (gap == "plugin") "compiler_plugin_rewrite_unavailable" else "compiler_arguments_require_verified_rewrite")
            assertEquals("pending", plan.assembly.plan.slots.single { it.input.context.scope.module == ":app" }.state)
        }
    }

    @Test fun removedAndRetargetedContextsRemainWholeCatalogAdministrativeObligations() {
        for (kind in listOf("removed", "retargeted")) OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val previous = f.capture()
            val before = f.import(previous, intent)
            val capture = if (kind == "removed") f.capture(f.files().filterKeys { !it.startsWith("app/") }) else previous
            val graph = if (kind == "removed") f.graph(capture, listOf(":lib")) else f.graph(capture).let { graph ->
                graph.copy(compilations = graph.compilations.map { if (it.moduleId == ":lib") it.copy(target = "desktop") else it })
            }
            val after = f.import(capture, intent, graph)
            val prepared = c.plans.prepare(intent, before, after, capture)
            assertEquals(after.compilationIds.size + 1, prepared.assembly.plan.slots.size)
            val old = prepared.assembly.plan.slots.single { it.state == "unsupported" }
            assertContains(old.reasons, if (kind == "removed") "required_compilation_removed_unresolved" else "required_context_retargeted_unresolved")
            assertEquals(f.catalog.ids.sorted(), old.input.requiredRuleIds)
            assertTrue(old.input.context.compilationFiles!!.isEmpty())
            assertEquals(after.snapshot, old.input.snapshot)
            assertNotNull(c.plans.policy(f.binding(), old.input))
            assertTrue(c.plans.evidence(old.input)!!.profiles.isEmpty())
            assertEquals(after.compilationIds.size, prepared.assembly.plan.slots.count { it.state == "pending" })
        }
    }

    @Test fun intentCannotMoveBetweenOwnerInstancesSessionsOrBeReplayed() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            assertEquals("permission_denied", assertFailsWith<CliProblem> {
                c.plans.begin(f.binding().copy(ownerId = "another-owner"), f.request(), true)
            }.code)
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val model = f.import(capture, intent)
            assertEquals("model_intent_invalid_or_replayed", assertFailsWith<CliProblem> {
                f.composition().plans.prepare(intent, null, model, capture)
            }.code)
            val assembled = c.plans.prepare(intent, null, model, capture)
            assertNull(c.plans.resolve(f.binding().copy(sessionId = "another-session"), f.request()))
            assertNull(c.plans.policy(f.binding().copy(sessionId = "another-session"), assembled.assembly.plan.slots.first().input))
            assertEquals("model_intent_invalid_or_replayed", assertFailsWith<CliProblem> {
                c.plans.prepare(intent, null, model, capture)
            }.code)
            val fake = OwnerModelIntent(Any(), intent.evidence, intent.configuration)
            assertEquals("model_intent_invalid_or_replayed", assertFailsWith<CliProblem> {
                c.plans.prepare(fake, null, model, capture)
            }.code)
        }
    }

    @Test fun failureHalfwayThroughRetentionCannotPublishAnExecutablePrefixOrPolicy() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition(maximumInputs = 1)
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val model = f.import(capture, intent)
            assertEquals("model_state_limit_or_invalid", assertFailsWith<CliProblem> {
                c.plans.prepare(intent, null, model, capture)
            }.code)
            val files = Files.list(f.state.namespace("model-inputs")).use { it.toList() }
            assertEquals(1, files.size, "This failure must occur AFTER a real provisional sibling was retained")
            val provisional = f.state.read<ModelInputEvidence>(files.single())
            assertNull(c.plans.evidence(provisional.input))
            assertNull(c.plans.policy(f.binding(), provisional.input))
            assertNull(c.plans.resolve(f.binding(), f.request()))
            assertEquals(0, c.attempts.activeCount())
            assertEquals(0L, Files.list(f.state.namespace("model-plans")).use { it.count() })
            val restored = f.composition()
            assertNull(restored.plans.evidence(provisional.input))
            assertNull(restored.plans.policy(f.binding(), provisional.input))
            assertNull(restored.plans.resolve(f.binding(), f.request()))
        }
    }

    @Test fun concurrentEquivalentInputsCannotBorrowAnotherClientsApprovalOrReplaceItsPolicy() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intents = listOf("agent-a", "agent-b").map { c.plans.begin(f.binding(it), f.request(it), true) }
            val capture = f.capture()
            val model = f.import(capture, intents.first())
            val pool = Executors.newFixedThreadPool(2)
            val outcomes = try {
                pool.invokeAll(intents.map { intent -> Callable { runCatching { c.plans.prepare(intent, null, model, capture) } } })
                    .map { it.get(20, TimeUnit.SECONDS) }
            } finally { pool.shutdownNow() }
            assertEquals(1, outcomes.count { it.isSuccess })
            val failure = assertIs<CliProblem>(outcomes.single { it.isFailure }.exceptionOrNull())
            assertEquals("model_cross_client_authority_unavailable", failure.code)
            val winner = outcomes.single { it.isSuccess }.getOrThrow()
            val loser = intents.single { it.binding != winner.assembly.plan.binding }
            winner.assembly.plan.slots.forEach { slot ->
                assertEquals(winner.assembly.plan.binding, c.plans.evidence(slot.input)!!.binding)
                assertNotNull(c.plans.policy(winner.assembly.plan.binding, slot.input))
                assertNull(c.plans.policy(loser.binding, slot.input))
            }
            assertNull(c.plans.resolve(loser.binding, loser.request))
            assertEquals(winner.assembly.plan, c.plans.resolve(winner.assembly.plan.binding, winner.request))
        }
    }

    @Test fun sameOwnerBindingCannotRebindCapturedInputToAnotherRequest() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val first = c.plans.begin(f.binding(), f.request("first"), true)
            val second = c.plans.begin(f.binding(), f.request("second"), true)
            val capture = f.capture()
            val model = f.import(capture, first)
            val plan = c.plans.prepare(first, null, model, capture)
            assertEquals("model_prepared_input_conflict", assertFailsWith<CliProblem> {
                c.plans.prepare(second, null, model, capture)
            }.code)
            assertEquals(plan.assembly.plan, c.plans.resolve(first.binding, first.request))
            assertNull(c.plans.resolve(second.binding, second.request))
        }
    }

    @Test fun equalBytesRelocatedOrDifferentLiveInstallationCannotBePassedOffAsTheModeledOne() {
        for (mismatch in listOf("relocated", "different-object")) OwnerModelFixtures().use { f ->
            f.setEnabled()
            val selected = if (mismatch == "relocated") f.alternativeInstallation(reverseOrder = true) else f.installation
            val providerInstallation = if (mismatch == "different-object")
                KotlinJvmToolchain(f.installation.javaExecutable, f.installation.compilerClasspath) else selected
            val c = f.composition(selected, providerInstallation)
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val model = f.import(capture, intent)
            val plan = c.plans.prepare(intent, null, model, capture)
            val reason = if (mismatch == "relocated") "compiler_model_compiler_artifacts_mismatch" else "compiler_model_provider_mismatch"
            assertEquals(setOf(reason), plan.preparationReasons.values.toSet())
            assertTrue(plan.assembly.plan.slots.all { it.state == "unsupported" && it.input.requiredRuleIds == f.catalog.ids.sorted() })
            assertEquals(0, c.attempts.activeCount())
        }
    }

    @Test fun registeredCaptureIdentityMustMatchAndDriftCannotBeAcceptedByRegistryResolution() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val model = f.import(capture, intent)
            val plan = c.plans.prepare(intent, null, model, capture)
            val selected = capture.root.resolve("lib/src/main/kotlin/Api.kt")
            selected.toFile().setWritable(true, true)
            Files.writeString(selected, "changed after preparation\n")
            assertEquals("materialization_unavailable", assertFailsWith<ReviewControllerException> {
                c.plans.resolve(f.binding(), plan.request)
            }.code)
            assertEquals(0, c.attempts.activeCount())
        }
    }

    @Test fun materializationFromAnotherRegisteredSnapshotCannotBeSubstitutedBeforeAdmission() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val model = f.import(capture, intent)
            val other = f.capture(f.files() + ("lib/src/main/kotlin/Api.kt" to "fun apiValue() = 9\n"))
            assertEquals("model_materialization_mismatch", assertFailsWith<CliProblem> {
                c.plans.prepare(intent, null, model, other)
            }.code)
            assertNull(c.plans.resolve(f.binding(), f.request()))
            assertEquals(0, c.attempts.activeCount())
        }
    }

    @Test fun exactArchivedCompilerChildRetainsWholeParentPolicyAndNeverReadsRemovedOriginalInputs() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val plan = c.plans.prepare(intent, null, f.import(capture, intent), capture)
            val original = plan.assembly.plan.slots.first().input
            val archive = f.archive(original)
            val evidence = assertNotNull(c.plans.evidence(original))
            f.removeOriginalInputs(capture)
            c.plans.observe(original, archive.effective, archive::verify)
            val child = c.attempts.child(archive.effective, evidence.compilerRuleIds)
            assertEquals(archive.effective.copy(requiredRuleIds = evidence.compilerRuleIds,
                execution = evidence.profiles.getValue(c.compiler!!.id)), child)
            assertEquals(original.snapshot, child.snapshot)
            assertEquals(original.context.compilationFiles, child.context.compilationFiles)
            assertEquals(original.context.policies, child.context.policies)
            assertTrue(archive.verifications >= 2)
            assertNotNull(c.attempts.executionAuthority.authorize(child))
            val modelPermit = assertNotNull(c.attempts.modelAuthority.authorize(child))
            val childExecution = assertNotNull(child.execution)
            listOf(child.copy(requiredRuleIds = emptyList()), child.copy(execution = null),
                child.copy(execution = childExecution.copy(arguments = childExecution.arguments + "-nowarn")),
                child.copy(context = child.context.copy(policies = emptyMap())),
                child.copy(outputRoot = f.work.resolve("other-output").toString())).forEach { foreign ->
                assertNull(c.attempts.executionAuthority.authorize(foreign))
                assertNull(c.attempts.modelAuthority.authorize(foreign))
            }
            val taggedDifferently = archive.effective.copy(context = archive.effective.context.copy(policies = emptyMap()))
            assertTrue(c.attempts.routes(taggedDifferently), "A changed tag cannot select a legacy fallback while this owner output is live")
            assertEquals("model_attempt_projection_mismatch", assertFailsWith<CliProblem> {
                c.attempts.child(taggedDifferently, evidence.compilerRuleIds)
            }.code)
            assertEquals("model_attempt_projection_mismatch", assertFailsWith<CliProblem> {
                c.attempts.child(archive.effective, emptyList())
            }.code)
            c.attempts.finish(archive.effective)
            c.attempts.finish(archive.effective)
            assertEquals(0, c.attempts.activeCount())
            assertFalse(modelPermit.accepts(child))
            assertNull(c.attempts.modelAuthority.authorize(child))
            assertNull(c.attempts.executionAuthority.authorize(child))
        }
    }

    @Test fun attemptTeardownIsExactAndCapacityFailureCannotReplaceAnotherActiveGrant() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition(maximumAttempts = 1)
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val plan = c.plans.prepare(intent, null, f.import(capture, intent), capture)
            val original = plan.assembly.plan.slots.first().input
            val first = f.archive(original, "first")
            val second = f.archive(original, "second")
            c.plans.observe(original, first.effective, first::verify)
            val child = c.attempts.child(first.effective, listOf(f.rule))
            val firstPermit = assertNotNull(c.attempts.modelAuthority.authorize(child))
            assertEquals("model_attempt_capacity_or_identity_conflict", assertFailsWith<KernelException> {
                c.plans.observe(original, second.effective, second::verify)
            }.code)
            c.attempts.finish(second.effective)
            val sameOutput = second.effective.copy(outputRoot = first.effective.outputRoot)
            assertEquals("model_attempt_capacity_or_identity_conflict", assertFailsWith<KernelException> {
                c.plans.observe(original, sameOutput, second::verify)
            }.code)
            c.attempts.finish(sameOutput)
            assertEquals(1, c.attempts.activeCount())
            assertTrue(firstPermit.accepts(child))
            c.attempts.finish(first.effective)
            c.plans.observe(original, second.effective, second::verify)
            assertFalse(firstPermit.accepts(child))
            assertNotNull(c.attempts.modelAuthority.authorize(c.attempts.child(second.effective, listOf(f.rule))))
            c.attempts.finish(second.effective)
            assertEquals(0, c.attempts.activeCount())
            assertFailsWith<IllegalStateException> {
                c.plans.observe(original, first.effective) { error("synthetic archive verifier refused") }
            }
            assertEquals(0, c.attempts.activeCount())
        }
    }

    @Test fun restartRestoresOnlyCommittedPoliciesAndEvidenceNotLiveAuthorityOrModelPermits() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val prepared = c.plans.prepare(intent, null, f.import(capture, intent), capture)
            val original = prepared.assembly.plan.slots.first().input
            val archive = f.archive(original)
            val restored = f.composition()
            assertEquals(c.plans.evidence(original), restored.plans.evidence(original))
            assertEquals(intent.configuration.revisionToken, restored.plans.policy(f.binding(), original)!!.revisionToken)
            assertEquals("model_live_authority_unavailable", assertFailsWith<KernelException> {
                restored.plans.resolve(f.binding(), prepared.request)
            }.code)
            assertEquals("model_live_authority_unavailable", assertFailsWith<KernelException> {
                restored.plans.observe(original, archive.effective, archive::verify)
            }.code)
            assertEquals(0, archive.verifications, "Restored approval evidence must not even invoke an attempt verifier")
            assertEquals(0, restored.attempts.activeCount())
            assertEquals("model_intent_invalid_or_replayed", assertFailsWith<CliProblem> {
                restored.plans.prepare(intent, null, prepared.assembly.provenance.after, capture)
            }.code)
        }
    }
}

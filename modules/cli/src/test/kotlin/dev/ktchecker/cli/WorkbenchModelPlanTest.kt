package dev.ktchecker.cli

import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real Workbench routing/admission; importer receipts and toolchain files are nonexecuted unit data. */
class WorkbenchModelPlanTest {
    private fun enroll(work: Workbench, f: OwnerModelFixtures): ClientRegistration {
        val binding = f.binding()
        val registration = work.register(binding.workspaceId, binding.worktreeId, binding.clientId, binding.sessionId, binding.lineageId)
        val revision = work.configs.capture(modelConfigBinding(binding)).revisionToken
        work.mutateConfig(binding.clientId, ConfigLayer.SHARED, revision, ConfigAction.SetRuleEnabled(f.rule, true))
        return registration
    }

    @Test fun typedSameInstallationMakesWholeParentAdmissionExecutableButNeverAllowsScalarBypassOrFinalization() {
        OwnerModelFixtures().use { f ->
            val options = WorkbenchOptions(state = f.state.root, workerCount = 0, modelCompilerInstallation = f.installation)
            lateinit var accepted: ParentPlanStatus
            lateinit var client: ClientRegistration
            lateinit var request: SubmitPlanRequest
            Workbench(options).use { work ->
                client = enroll(work, f)
                val intent = work.beginModelReview(client.binding.clientId, f.request(), true)
                val capture = f.capture()
                val prepared = work.prepareModelReview(intent, null, f.import(capture, intent), capture)
                request = prepared.request
                assertTrue(prepared.preparationReasons.isEmpty())
                assertTrue(prepared.assembly.plan.slots.all { it.state == "pending" })
                prepared.assembly.plan.slots.forEach { slot ->
                    assertEquals(f.catalog.ids.sorted(), slot.input.requiredRuleIds)
                    assertEquals("model_parent_plan_required", assertFailsWith<CliProblem> {
                        work.submit(client.client, request.patchId, request.idempotencyKey, slot.input)
                    }.code)
                    val edited = slot.input.copy(context = slot.input.context.copy(policies = emptyMap()))
                    assertEquals("unprepared_submission", assertFailsWith<CliProblem> {
                        work.submit(client.client, request.patchId, request.idempotencyKey, edited)
                    }.code)
                }
                assertTrue(work.kernel.events(client.client).events.isEmpty())
                accepted = work.submitModelPlan(client.client, request)
                assertEquals(prepared.assembly.plan.slots.size, accepted.slots.size)
                assertEquals(accepted.planId, work.submitModelPlan(client.client, request).planId)
                val gate = work.kernel.prepareGate(client.client)
                assertFalse(gate.ready)
                assertContains(gate.reasons, "snapshot_vector_authority_unavailable")
                assertFalse(work.kernel.completeGate(client.client, gate.cutId).allowed)
                assertEquals("method_not_found", assertFailsWith<CliProblem> {
                    work.call("review.model.import", buildJsonObject {})
                }.code)
            }
            Workbench(options).use { work ->
                assertEquals(accepted.planId, work.submitModelPlan(client.client, request).planId)
                assertEquals(accepted.slots.map { it.reviewId }, work.kernel.planStatus(client.client, accepted.planId).slots.map { it.reviewId })
                assertTrue(work.kernel.planStatus(client.client, accepted.planId).slots.all { it.state == "queued" })
                assertFalse(work.kernel.prepareGate(client.client).ready)
            }
        }
    }

    @Test fun missingExplicitModelSelectionCannotFallbackEvenWhenLegacyInstallationIsAvailable() {
        OwnerModelFixtures().use { f ->
            val options = WorkbenchOptions(state = f.state.root, workerCount = 0,
                compilerDirectory = Path.of(f.compilerArtifacts.first().path).parent,
                compilerJavaHome = f.installation.runtimeImage.root)
            Workbench(options).use { work ->
                val client = enroll(work, f)
                assertEquals("toolchain_bound", work.providerAvailability().getValue("compiler").jsonObject.getValue("state").jsonPrimitive.content)
                val intent = work.beginModelReview(client.binding.clientId, f.request(), true)
                val capture = f.capture()
                val model = f.import(capture, intent)
                val prepared = work.prepareModelReview(intent, null, model, capture)
                assertEquals(model.compilationIds.toSet(), prepared.preparationReasons.keys)
                assertEquals(setOf("model_compiler_installation_unavailable"), prepared.preparationReasons.values.toSet())
                assertTrue(prepared.assembly.plan.slots.all { it.state == "unsupported" && it.input.requiredRuleIds == f.catalog.ids.sorted() })
                val accepted = work.submitModelPlan(client.client, prepared.request)
                assertEquals("incomplete", accepted.state)
                assertEquals(model.compilationIds.size, accepted.slots.size)
                assertTrue(accepted.slots.all { it.state == "unsupported" })
                assertFalse(work.kernel.prepareGate(client.client).ready)
            }
        }
    }

    @Test fun oneOwnerInstanceCannotPrepareAnotherInstancesCapturedIntentEvenWithSameStateOrModelJson() {
        OwnerModelFixtures().use { f ->
            val options = WorkbenchOptions(state = f.state.root, workerCount = 0, modelCompilerInstallation = f.installation)
            Workbench(options).use { first ->
                val client = enroll(first, f)
                val intent = first.beginModelReview(client.binding.clientId, f.request(), true)
                val capture = f.capture()
                val model = f.import(capture, intent)
                Workbench(options).use { second ->
                    assertEquals("model_intent_invalid_or_replayed", assertFailsWith<CliProblem> {
                        second.prepareModelReview(intent, null, model, capture)
                    }.code)
                    assertTrue(second.kernel.events(client.client).events.isEmpty())
                }
                val prepared = first.prepareModelReview(intent, null, model, capture)
                assertTrue(prepared.preparationReasons.isEmpty())
                assertEquals("pending", first.submitModelPlan(client.client, prepared.request).state)
            }
        }
    }
}

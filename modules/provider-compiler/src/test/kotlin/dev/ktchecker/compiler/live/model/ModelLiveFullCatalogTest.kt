package dev.ktchecker.compiler.live.model

import dev.ktchecker.contracts.*
import dev.ktchecker.reviewcontroller.OwnerCatalogInventory
import dev.ktchecker.reviewcontroller.PlanActivation
import dev.ktchecker.reviewcontroller.PlanRuleActivation
import java.util.AbstractList
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual distributed catalog rows, synthetic model/activation authority; no execution evidence. */
class ModelLiveFullCatalogTest {
    @Test fun all1665OwnerRowsAndTheirActivationVectorSurvivePkcEWithoutParentNarrowing(): Unit = ModelLiveHostFixture().use { f ->
        val records = listOf("core", "libraries", "platform", "ecosystem").flatMap { name ->
            val resource = requireNotNull(javaClass.getResourceAsStream("/model-live-control-catalog/rules-$name.json")) {
                "Full-catalog control requires the exact current distribution inventory, never synthetic replacement IDs"
            }
            resource.use { ContractJson.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonArray.map { element -> element.jsonObject } }
        }
        assertEquals(1665, records.size, "A changed full catalog requires deliberate review of this boundary control")
        val inventory = OwnerCatalogInventory(Digests.sha256(Digests.canonical(JsonArray(records))),
            records.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(1665, inventory.ruleIds.size)
        assertEquals(inventory.ruleIds.distinct().sorted(), inventory.ruleIds)
        assertEquals("sha256:121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5",
            Digests.sha256(Digests.canonical(JsonArray(inventory.ruleIds.map(::JsonPrimitive)))))
        assertContains(inventory.ruleIds, "ktc.compiler.common.error_suppression")
        assertContains(inventory.ruleIds, "ktc.compiler.common.inapplicable_operator_modifier")
        val activation = PlanActivation(f.modelFixture.configuration, inventory.ruleIds.map {
            PlanRuleActivation(it, true, "synthetic-captured-owner-policy", "Full parent-vector preservation control only")
        })
        val activationText = Digests.canonical(ContractJson.encodeToJsonElement(activation))
        assertTrue(activationText.toByteArray().size > 4096) // This is not a short policy scalar.
        val original = f.original.copy(requiredRuleIds = inventory.ruleIds, context = f.original.context.copy(
            policies = f.original.context.policies + mapOf("ktchecker.catalog-inventory" to inventory.catalogDigest,
                "ktchecker.activation-plan" to activationText)))
        val prepared = f.cold.binding.bindPreparedLiveInput(original, f.prepared.compilerRuleIds, f.provider, f.originalDeadline)
        val archive = f.archive(prepared)
        val permit = f.grant(archive, prepared)
        assertEquals(inventory.ruleIds, prepared.original.requiredRuleIds)
        assertEquals(inventory.ruleIds, permit.archived.requiredRuleIds)
        assertEquals(prepared.compilerRuleIds, permit.input.requiredRuleIds)
        assertTrue(permit.input.requiredRuleIds.size < inventory.ruleIds.size)
        permit.consume()
        val materialized = ModelCompilerJobMaterialization.create(f.request, permit, 1) {}
        for (input in listOf(materialized.original, materialized.archived, materialized.input, materialized.effective)) {
            assertEquals(activationText, input.context.policies["ktchecker.activation-plan"])
            assertEquals(1665, ContractJson.parseToJsonElement(input.context.policies.getValue("ktchecker.activation-plan"))
                .jsonObject.getValue("decisions").jsonArray.size)
            assertEquals(inventory.catalogDigest, input.context.policies["ktchecker.catalog-inventory"])
            assertEquals(original.snapshot, input.snapshot)
            assertEquals(original.context.modelDigest, input.context.modelDigest)
            assertNull(input.execution)
        }
        assertEquals(inventory.ruleIds, materialized.original.requiredRuleIds)
        assertEquals(inventory.ruleIds, materialized.archived.requiredRuleIds)
        assertEquals(prepared.compilerRuleIds, materialized.effective.requiredRuleIds)
        assertEquals(prepared.originalAnalysisKey, materialized.wireRequest.ownerInputKey())
        assertEquals(permit.archivedInputKey, materialized.wireRequest.archivedInputKey())
        assertEquals(0, f.session.submissions)
    }

    @Test fun parentAndCompilerProjectionHaveDistinctFiniteBounds(): Unit = ModelLiveHostFixture().use { f ->
        assertEquals(4096, ModelLiveLimits.PARENT_RULES)
        assertEquals(1024, ModelLiveLimits.COMPILER_RULES)
        val wide = f.original.copy(requiredRuleIds = List(1665) { "synthetic.parent.$it" })
        assertEquals(wide, freezeModelLiveInput(wide))
        expectModelLiveFailure("compiler_model_live_input_limit") { ModelCompilerAttemptRequest(wide) }
        val tooMany = object : AbstractList<String>() {
            override val size: Int get() = ModelLiveLimits.PARENT_RULES + 1
            override fun get(index: Int): String = error("Oversized full parent must not be iterated or silently narrowed")
        }
        expectModelLiveFailure("compiler_model_live_input_limit") { freezeModelLiveInput(f.original.copy(requiredRuleIds = tooMany)) }
    }
}

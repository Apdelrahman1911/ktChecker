package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.PREVIEW_CONTRACT
import kotlinx.serialization.json.*

/** Inventory data is distributed with the engine. It never grants execution or qualification. */
internal class Catalog {
    val records: List<JsonObject> by lazy {
        listOf("core", "libraries", "platform", "ecosystem").flatMap { name ->
            val stream = Catalog::class.java.getResourceAsStream("/catalog/rules-$name.json")
                ?: throw CliProblem("catalog_unavailable", "The distribution is missing required catalog data.")
            stream.use { ContractJson.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonArray.map(JsonElement::jsonObject) }
        }.also { entries ->
            val ids = entries.map { it.getValue("id").jsonPrimitive.content }
            check(ids.size == ids.toSet().size) { "Duplicate stable rule identity" }
        }
    }

    val ids: Set<String> get() = records.map { it.getValue("id").jsonPrimitive.content }.toSet()
    fun applicableInventory(target: String): List<String> {
        val normalized = when {
            target.startsWith("ios", true) -> "ios"
            target.contains("android", true) -> "android"
            target in setOf("jvm", "desktop") -> "jvm"
            else -> "common"
        }
        return records.filter {
            it.getValue("default_activation").jsonPrimitive.content != "excluded" &&
                it.getValue("platforms").jsonArray.any { platform -> platform.jsonPrimitive.content in setOf(normalized, "common") }
        }.map { it.getValue("id").jsonPrimitive.content }.sorted()
    }

    fun inspect(id: String? = null): JsonObject = buildJsonObject {
        put("contractVersion", PREVIEW_CONTRACT); put("command", "rules")
        put("releaseQualified", false)
        put("inventoryCount", records.size)
        put("note", "Catalog entries are requirements, not implemented or qualified coverage. Use the evidence-backed coverage report.")
        if (id == null) {
            putJsonObject("activationCounts") {
                records.groupingBy { it.getValue("default_activation").jsonPrimitive.content }.eachCount().toSortedMap().forEach { (k, v) -> put(k, v) }
            }
        } else {
            put("rule", records.singleOrNull { it.getValue("id").jsonPrimitive.content == id }
                ?: throw CliProblem("unknown_rule", "Stable rule ID is not in the agreed inventory."))
        }
    }
}

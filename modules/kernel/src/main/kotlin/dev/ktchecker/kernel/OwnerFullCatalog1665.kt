package dev.ktchecker.kernel

import dev.ktchecker.contracts.Digests
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deliberately reviewed full-owner vector successor, not a provider subset or a runtime switch.
 * The exact sorted-ID digest preserves every old1663 ID and adds only the two reviewed compiler
 * diagnostics. Record metadata/activation remain separately bound by the existing catalogDigest;
 * this contract grants no execution, defaults, testing, precision or qualification.
 */
object OwnerFullCatalog1665 {
    const val ID = "ktchecker/owner-full-catalog/1665-u2/1"
    const val RULE_COUNT = 1665
    const val RULE_IDS_DIGEST = "sha256:121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5"
    const val ERROR_SUPPRESSION = "ktc.compiler.common.error_suppression"
    const val INAPPLICABLE_OPERATOR_MODIFIER = "ktc.compiler.common.inapplicable_operator_modifier"

    /** No sorting/repair, legacy fallback, caller-supplied digest or silently missing obligations. */
    fun accepts(ruleIds: List<String>): Boolean = ruleIds.size == RULE_COUNT &&
        ruleIds == ruleIds.distinct().sorted() &&
        Digests.sha256(Digests.canonical(JsonArray(ruleIds.map(::JsonPrimitive)))) == RULE_IDS_DIGEST
}

internal const val PRODUCED_CATALOG_GENERATION_UNSUPPORTED = "produced_catalog_generation_unsupported"

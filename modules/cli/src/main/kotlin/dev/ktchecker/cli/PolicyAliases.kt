package dev.ktchecker.cli

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.providers.detekt.DetektCatalog
import dev.ktchecker.providers.lint.LintCatalog
import dev.ktchecker.providers.ktlint.KtlintCatalog
import kotlinx.serialization.json.*

/** Routing equivalence only: aliases never grant qualification, enable a rule or merge overlaps. */
internal data class RoutingAlias(val canonicalId: String, val names: Set<String>)

internal fun policyAliases(catalog: Catalog): Map<String, Set<String>> {
    val candidates = buildList {
        CompilerRuleRegistry.load().mappings.forEach { row ->
            add(RoutingAlias(row.ruleId, setOf(row.upstreamId, "compiler:${row.upstreamId}")))
        }
        DetektCatalog.mappings.filter { it.exact }.forEach { row ->
            add(RoutingAlias(row.rule_id, setOf(row.upstream_id, row.sarifId, "detekt:${row.upstream_id}")))
        }
        KtlintCatalog.mappings.filter { it.exact }.forEach { row ->
            add(RoutingAlias(row.rule_id, setOf(row.upstream_id, "ktlint:${row.upstream_id}")))
        }
        LintCatalog.rules.filter { it.activation != "excluded" }.forEach { row ->
            // Artifact/registry qualification is required for colliding navigation issue names.
            add(RoutingAlias(row.ruleId, setOf(row.upstreamId, "lint:${row.registry}:${row.upstreamId}")))
        }
    }
    return unambiguousAliases(catalog, candidates)
}

internal fun unambiguousAliases(catalog: Catalog, candidates: List<RoutingAlias>): Map<String, Set<String>> {
    val canonicalIds = catalog.ids
    val allowed = catalog.records.filter { it.getValue("default_activation").jsonPrimitive.content != "excluded" }
        .map { it.getValue("id").jsonPrimitive.content }.toSet()
    val ownership = mutableMapOf<String, MutableSet<String>>()
    // Include known excluded/refinement owners when testing ambiguity. An unavailable owner
    // must not make a colliding short name appear uniquely equivalent to a different detector.
    catalog.records.forEach { row ->
        val id = row.getValue("id").jsonPrimitive.content
        val names = listOfNotNull(row["upstream_id"]?.jsonPrimitive?.contentOrNull) +
            row["upstream_aliases"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        (names + id).forEach { ownership.getOrPut(it) { mutableSetOf() }.add(id) }
    }
    candidates.forEach { row -> row.names.forEach { ownership.getOrPut(it) { mutableSetOf() }.add(row.canonicalId) } }
    val aliases = mutableMapOf<String, MutableSet<String>>()
    candidates.filter { it.canonicalId in allowed }.forEach { row ->
        row.names.filter { ownership[it] == setOf(row.canonicalId) && it !in canonicalIds }.forEach { name ->
            aliases.getOrPut(row.canonicalId) { mutableSetOf() }.add(name)
        }
    }
    return aliases.mapValues { (_, names) -> names.toSortedSet().toSet() }.toSortedMap()
}

package dev.ktchecker.cli

import dev.ktchecker.config.PolicyRule
import dev.ktchecker.config.RuleAliases
import kotlin.test.*

class PolicyAliasesTest {
    @Test fun exactProviderAliasesResolveWithoutMergingNarrowerOrAmbiguousRules() {
        val catalog = Catalog()
        val names = policyAliases(catalog)
        val registry = RuleAliases(catalog.ids.map { PolicyRule(it, names[it].orEmpty()) })
        assertEquals("ktc.compiler.common.abstract_member_not_implemented", registry.canonicalId("ABSTRACT_MEMBER_NOT_IMPLEMENTED"))
        assertEquals("ktc.kotlin.detekt.style.magic-number", registry.canonicalId("style:MagicNumber"))
        assertEquals(registry.canonicalId("style:MagicNumber"), registry.canonicalId("detekt.style.MagicNumber"))
        assertEquals("ktc.android.lint.NewApi", registry.canonicalId("NewApi"))
        // Different navigation artifacts can expose the same short diagnostic name.
        assertNull(registry.canonicalId("WrongStartDestinationType"))
        assertNull(registry.canonicalId("SuspendFunSwallowedCancellation"))
        assertNull(registry.canonicalId("coroutines:SuspendFunSwallowedCancellation"))
        assertNull(registry.canonicalId("detekt.coroutines.SuspendFunSwallowedCancellation"))
        assertNull(registry.canonicalId("Compose:ComposableNaming"))
        assertEquals(catalog.ids, registry.allRules().map { it.canonicalId }.toSet())
    }

    @Test fun ambiguousOrCanonicalAliasClaimsFailClosedRatherThanChoosingFirst() {
        val catalog = Catalog()
        val a = "ktc.architecture.allowed_dependency_edge"
        // Use actual IDs, not a registry alias that silently creates a missing rule.
        val other = catalog.ids.first { it != a }
        val result = unambiguousAliases(catalog, listOf(RoutingAlias(a, setOf("shared:name", other, "exact:a")),
            RoutingAlias(other, setOf("shared:name"))))
        assertEquals(setOf("exact:a"), result[a])
        assertFalse(result.values.any { "shared:name" in it || other in it })
    }
}

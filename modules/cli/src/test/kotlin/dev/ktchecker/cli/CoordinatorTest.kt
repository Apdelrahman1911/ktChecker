package dev.ktchecker.cli

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.detekt.DetektProvider
import dev.ktchecker.providers.ktlint.KtlintProvider
import kotlinx.serialization.json.*
import kotlin.test.*

/** Routing/cache controls, not upstream rule execution or qualification. */
class CoordinatorTest {
    private val catalog = Catalog()
    private val policy = Digests.sha256("captured-policy")
    private val image = CodeImage.capture(listOf(ReviewCoordinator::class.java))

    private fun input(rules: List<String>, compiler: String = "2.2.21", target: String = "jvm"): AnalysisInput {
        val activation = activationPlan(catalog, rules, true, emptyMap(), policy)
        return AnalysisInput(SnapshotManifest("snapshot", "project", "tree", listOf(
            SnapshotFile("Main.kt", Digests.sha256("fun main() {}"), 13),
            SnapshotFile(".editorconfig", Digests.sha256("root=true\n"), 10),
        )), "/sealed", AnalysisContext(scope = RuleContext(target), compilerVersion = compiler,
            configurationDigest = policy, compilationFiles = listOf("Main.kt"),
            policies = mapOf(ACTIVATION_POLICY_KEY to ContractJson.encodeToString(ActivationPlan.serializer(), activation))), rules)
    }

    private fun coordinator(installed: Boolean): ReviewCoordinator {
        val ktlint = KtlintProvider()
        val detekt = DetektProvider()
        return ReviewCoordinator(catalog, listOf(
            ProviderRoute(ktlint, { ktlintPreferred(it, installed) }),
            ProviderRoute(detekt, { true }),
            ProviderRoute(ktlint, { !ktlintPreferred(it, installed) }),
        ), ExecutionGrants(), image)
    }

    @Test fun compatibleInstalledKtlintClaimsItsOverlappingSyntaxRulesExactlyOnce() {
        val overlap = KtlintProvider().supportedRuleIds.intersect(DetektProvider().supportedRuleIds)
        assertTrue(overlap.isNotEmpty(), "Real maintained catalogs must exercise overlapping routes")
        val required = overlap.sorted()
        val routes = coordinator(true).plannedRoutes(input(required))
        assertEquals(listOf("ktlint"), routes.keys.map { it.provider.id })
        assertEquals(required, routes.values.single())
    }

    @Test fun unavailableOrIncompatibleKtlintNeverStealsOverlapFromDetekt() {
        val rule = KtlintProvider().supportedRuleIds.intersect(DetektProvider().supportedRuleIds).sorted().first()
        val base = input(listOf(rule))
        val cases = listOf(
            false to base,
            true to input(listOf(rule), compiler = "2.4.21"),
            true to input(listOf(rule), target = "iosArm64"),
            true to input(listOf(rule), target = "desktop"),
            true to base.copy(context = base.context.copy(compilerArguments = listOf("-Xcontext-parameters"))),
            true to base.copy(snapshot = base.snapshot.copy(unknownInputs = listOf("unresolved-generator"))),
            true to base.copy(snapshot = base.snapshot.copy(files = base.snapshot.files.filter { it.path != ".editorconfig" })),
            true to base.copy(context = base.context.copy(compilationFiles = null)),
        )
        cases.forEach { (installed, candidate) ->
            assertFalse(ktlintPreferred(candidate, installed))
            assertEquals(listOf("detekt"), coordinator(installed).plannedRoutes(candidate).keys.map { it.provider.id })
        }
    }

    @Test fun ktlintOnlyUnsupportedTuplesStillHaveAnExplicitProviderAbstentionPath() {
        val ktlintOnly = (KtlintProvider().supportedRuleIds - DetektProvider().supportedRuleIds).sorted().first()
        val routes = coordinator(false).plannedRoutes(input(listOf(ktlintOnly), compiler = "2.4.21"))
        assertEquals(listOf("ktlint"), routes.keys.map { it.provider.id })
        assertEquals(listOf(ktlintOnly), routes.values.single())
    }

    @Test fun cacheVersionBindsCodeResourcesRouteOrderImplementationAndSupportedRules() {
        fun route(id: String, version: String = "same-label", rules: Set<String> = setOf("rule.a", "rule.b"),
                  implementation: String? = Digests.sha256("installed-bytes")) = ProviderRoute(object : AnalysisProvider {
            override val id = id
            override val version = version
            override val supportedRuleIds = rules
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult = error("Not executed")
        }, { true }, implementationIdentity = implementation)
        val a = route("a")
        val b = route("b")
        val digest = Digests.sha256("coordinator+normalizer+configuration+all-selected-resources")
        val initial = coordinatorVersion(digest, listOf(a, b))
        assertEquals(initial, coordinatorVersion(digest, listOf(route("a", rules = linkedSetOf("rule.b", "rule.a")), b)))
        assertNotEquals(initial, coordinatorVersion(Digests.sha256("changed-normalizer"), listOf(a, b)))
        assertNotEquals(initial, coordinatorVersion(digest, listOf(b, a)))
        assertNotEquals(initial, coordinatorVersion(digest, listOf(route("a", version = "new-label"), b)))
        assertNotEquals(initial, coordinatorVersion(digest, listOf(route("a", implementation = Digests.sha256("changed-upstream-bytes")), b)))
        assertNotEquals(initial, coordinatorVersion(digest, listOf(route("a", implementation = null), b)))
        assertNotEquals(initial, coordinatorVersion(digest, listOf(route("a", rules = setOf("rule.a")), b)))
        assertFailsWith<IllegalArgumentException> { coordinatorVersion("not-a-digest", listOf(a)) }
    }
}

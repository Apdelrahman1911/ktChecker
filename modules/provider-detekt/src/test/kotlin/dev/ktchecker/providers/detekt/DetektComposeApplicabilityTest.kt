package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** SOURCE/authority/projection controls only, never execution or Compose compiler qualification. */
class DetektComposeApplicabilityTest {
    @TempDir lateinit var temporary: Path
    private val remember = "ktc.compose.pack.RememberMissing"
    private val autoboxing = "ktc.compose.pack.MutableStateAutoboxing"
    private val mutableParam = "ktc.compose.pack.MutableStateParam"
    private val lambda = "ktc.compose.pack.LambdaParameterInRestartableEffect"
    private val state = "ktc.compose.pack.StateParam"
    private val eight = listOf(remember, "ktc.compose.pack.RememberContentMissing", lambda,
        "ktc.compose.pack.ContentSlotReused", autoboxing, state, mutableParam, "ktc.compose.pack.UnstableCollections")

    @Test fun `all 43 catalog obligations remain with default activation and aliases unchanged`() {
        val mappings = DetektCatalog.mappings.filter { it.upstream_id.startsWith("Compose:") }
        assertEquals(43, mappings.size)
        assertEquals(mappings.map { it.rule_id }.toSet(), DetektComposeSurfaces.ruleIds)
        assertEquals(2, mappings.count { it.relation == "excluded_alias" })
        assertEquals(setOf("advisory", "opt_in", "excluded", "enabled_when_validated"), mappings.map { it.default_activation }.toSet())
        val provider = DetektProvider()
        assertTrue(mappings.filter { it.exact }.all { it.rule_id in provider.supportedRuleIds })
        assertEquals(mappings.map { it.rule_id }.toSet(), provider.mappedRuleIds.filter { it in DetektComposeSurfaces.ruleIds }.toSet())
    }

    @Test fun `imports homonyms forged library tags and serialized approval cannot authorize Compose`() {
        val owned = fixture(eight, """
            import androidx.compose.runtime.mutableStateOf
            annotation class Composable
            @Composable fun localHomonym() { val state = mutableStateOf(0) }
        """.trimIndent())
        val claimed = owned.input.copy(context = owned.input.context.copy(
            libraries = mapOf("androidx.compose.runtime" to "1.12.1"), modelDigest = Digests.sha256("forged model"),
            dependencyDigest = Digests.sha256("forged dependencies"), toolchainDigest = Digests.sha256("forged toolchain")),
            execution = TrustedExecution("/never-launched", listOf(DetektLibraryPermit.PROFILE_PROPERTY + Digests.sha256("forged")), approved = true))
        val provider = DetektProvider(runner = IsolatedProcessRunner { fail("Denied Compose must not reach a runner") })
        val result = provider.analyze(claimed, CancellationToken.NONE)
        assertEquals(eight, result.coverage.map { it.ruleId })
        assertTrue(result.findings.isEmpty())
        assertTrue(result.coverage.all { it.state == "unavailable" && it.reason == "detekt_compose_library_not_authorized" })
        assertUnqualified(result)
    }

    @Test fun `unclassified process text cannot leak Compose advice on success failure or mixed requests`() {
        val owned = fixture(listOf(remember))
        val generic = DetektCatalog.mappings.first { !it.upstream_id.startsWith("Compose:") && it.exact }.rule_id
        val provider = DetektProvider()
        val collectionAdvice = "Using mutableIntListOf is recommended over mutableStateOf<List<Int>>"
        val lifecycleAdvice = "Unsafe lifecycle recommendation from an unattributed native stream"
        for (status in listOf("executed", "failed", "cancelled")) {
            // Synthetic data tests formatting only; no real execution/observation authority.
            val outcome = ProcessOutcome(status, stdout = collectionAdvice, stderr = lifecycleAdvice)
            assertFalse(outcome.successfulExecution)
            for (rules in listOf(listOf(remember), listOf(generic, remember))) {
                val raw = provider.processDiagnostics(owned.input.copy(requiredRuleIds = rules), outcome)
                assertEquals(listOf("info", "error"), raw.map { it.severity })
                assertTrue(raw.all { it.message.startsWith("Detekt process text withheld:") })
                assertTrue(raw.none { collectionAdvice in it.message || lifecycleAdvice in it.message })
            }
            val genericRaw = provider.processDiagnostics(owned.input.copy(requiredRuleIds = listOf(generic)), outcome)
            assertEquals(listOf(collectionAdvice, lifecycleAdvice), genericRaw.map { it.message })
        }
        assertTrue(provider.processDiagnostics(owned.input, ProcessOutcome("failed")).isEmpty())
        assertEquals(8192, provider.processDiagnostics(owned.input.copy(requiredRuleIds = listOf(generic)),
            ProcessOutcome("failed", stdout = "x".repeat(9000))).single().message.length)
    }

    @Test fun `owner verified compilation without runtime remains explicitly unavailable and does not launch`() {
        val owned = fixture(eight)
        var permit: DetektLibraryPermit? = null
        val provider = DetektProvider(libraryAuthority = DetektLibraryAuthority { permit },
            runner = IsolatedProcessRunner { fail("Missing runtime must not reach a runner") })
        val prepared = DetektLibraryPermit.prepare(provider, owned.input, DetektComposeCompilationOwner { owned.compilation })
        permit = prepared
        prepared.use {
            val assessed = it.verify(provider, owned.input)
            assertTrue(assessed.rules.values.all { rule -> !rule.executableFacets && rule.reason == "detekt_compose_library_missing" })
            val result = provider.analyze(owned.input, CancellationToken.NONE)
            assertEquals(eight, result.coverage.map { coverage -> coverage.ruleId })
            assertTrue(result.coverage.all { coverage -> coverage.state == "unavailable" && coverage.reason == "detekt_compose_library_missing" })
            assertUnqualified(result)
        }
    }

    @Test fun `actual owner bytes with a runtime label but wrong published identity never activate Compose`() {
        val untrusted = artifact(DetektComposeLibraryPins.runtime.coordinate, "not-the-published-runtime.jar", "not the runtime")
        val owned = fixture(listOf(state), dependencies = listOf(untrusted))
        var permit: DetektLibraryPermit? = null
        val provider = DetektProvider(libraryAuthority = DetektLibraryAuthority { permit },
            runner = IsolatedProcessRunner { fail("A correct label cannot authorize incorrect binary bytes") })
        val prepared = DetektLibraryPermit.prepare(provider, owned.input, DetektComposeCompilationOwner { owned.compilation })
        permit = prepared
        prepared.use {
            val result = provider.analyze(owned.input, CancellationToken.NONE)
            assertEquals("unavailable", result.coverage.single().state)
            assertEquals("detekt_compose_tuple_unsupported", result.coverage.single().reason)
            assertTrue(result.findings.isEmpty())
            assertUnqualified(result)
        }
    }

    @Test fun `scope compiler arguments and complete ordered membership must equal actual owner resolution`() {
        val owned = fixture(listOf(state))
        val provider = DetektProvider()
        val changes = listOf(
            owned.input.copy(context = owned.input.context.copy(scope = owned.input.context.scope.copy(target = "native"))),
            owned.input.copy(context = owned.input.context.copy(scope = owned.input.context.scope.copy(module = ":other"))),
            owned.input.copy(context = owned.input.context.copy(scope = owned.input.context.scope.copy(sourceSet = "test"))),
            owned.input.copy(context = owned.input.context.copy(scope = owned.input.context.scope.copy(compilation = "test"))),
            owned.input.copy(context = owned.input.context.copy(compilerVersion = "2.4.10")),
            owned.input.copy(context = owned.input.context.copy(compilerArguments = listOf("-Xplugin=untrusted.jar"))),
            owned.input.copy(context = owned.input.context.copy(compilationFiles = emptyList())),
            owned.input.copy(context = owned.input.context.copy(compilationFiles = listOf("source.kt", "source.kt"))),
            owned.input.copy(snapshot = owned.input.snapshot.copy(unknownInputs = listOf("unresolved"))),
            owned.input.copy(requiredRuleIds = listOf(state, state)),
        )
        val permit = DetektLibraryPermit.prepare(provider, owned.input, DetektComposeCompilationOwner { owned.compilation })
        permit.use {
            for (changed in changes) {
                assertFalse(it.accepts(changed))
                assertFailsWith<ExecutionProblem> {
                    DetektLibraryPermit.prepare(provider, changed, DetektComposeCompilationOwner { owned.compilation })
                }
            }
            assertFalse(it.accepts(owned.input.copy(requiredRuleIds = listOf(remember))))
            assertFalse(it.accepts(owned.input.copy(outputRoot = temporary.resolve("another-output").toString())))
        }
    }

    @Test fun `same scope spelling cannot change actual compilation ID or source set roster`() {
        val owned = fixture(listOf(state))
        val provider = DetektProvider()
        var selected = owned.compilation
        val permit = DetektLibraryPermit.prepare(provider, owned.input, DetektComposeCompilationOwner { selected })
        permit.use {
            selected = selected.copy(compilationId = "different-actual-compilation")
            assertEquals("detekt_compose_owner_resolution_changed", assertFailsWith<ExecutionProblem> { it.verify(provider, owned.input) }.code)
            selected = owned.compilation.copy(sourceSetIds = listOf("different-source-set-identity"))
            assertEquals("detekt_compose_owner_resolution_changed", assertFailsWith<ExecutionProblem> { it.verify(provider, owned.input) }.code)
        }
    }

    @Test fun `full classpath order paths coordinates and expected bytes are retained independently`() {
        val first = artifact("example:first:1", "first.jar", "first unrelated JVM fixture bytes")
        val second = artifact("example:second:1", "second.jar", "second unrelated JVM fixture bytes")
        val owned = fixture(listOf(state), dependencies = listOf(first, second))
        val provider = DetektProvider()
        val permit = DetektLibraryPermit.prepare(provider, owned.input, DetektComposeCompilationOwner { owned.compilation })
        permit.use {
            val reordered = owned.input.copy(context = owned.input.context.copy(classpath = owned.input.context.classpath.reversed()))
            assertFalse(it.accepts(reordered))
            assertEquals("detekt_compose_compilation_mismatch", assertFailsWith<ExecutionProblem> {
                DetektLibraryPermit.prepare(provider, reordered, DetektComposeCompilationOwner { owned.compilation })
            }.code)
            Files.writeString(Path.of(second.identity.path), "tampered unrelated dependency")
            assertEquals("detekt_compose_dependency_bytes_changed", assertFailsWith<ExecutionProblem> { it.verify(provider, owned.input) }.code)
        }
    }

    @Test fun `source byte drift is rejected independently of unchanged snapshot digest strings`() {
        val owned = fixture(listOf(autoboxing), "fun notComposable() { val state = mutableStateOf(0) }")
        val provider = DetektProvider()
        val permit = DetektLibraryPermit.prepare(provider, owned.input, DetektComposeCompilationOwner { owned.compilation })
        permit.use {
            Files.writeString(Path.of(owned.input.materializedRoot).resolve("source.kt"), "fun changed() {}")
            assertEquals("detekt_compose_source_bytes_changed", assertFailsWith<ExecutionProblem> { it.verify(provider, owned.input) }.code)
        }
    }

    @Test fun `source archives and symlink aliases cannot be classpath authority`() {
        val sources = artifact("androidx.compose.runtime:runtime:1.12.1:sources", "runtime-sources.jar", "not a JVM binary")
        val owned = fixture(listOf(state), dependencies = listOf(sources))
        assertEquals("detekt_compose_binary_role_invalid", assertFailsWith<ExecutionProblem> {
            DetektLibraryPermit.prepare(DetektProvider(), owned.input, DetektComposeCompilationOwner { owned.compilation })
        }.code)
        val target = artifact("example:binary:1", "real.jar", "unrelated test bytes")
        val link = temporary.resolve("linked.jar")
        Files.createSymbolicLink(link, Path.of(target.identity.path))
        val linked = fixture(listOf(state), dependencies = listOf(target.copy(identity = target.identity.copy(path = link.toString()))))
        assertEquals("detekt_compose_path_invalid", assertFailsWith<ExecutionProblem> {
            DetektLibraryPermit.prepare(DetektProvider(), linked.input, DetektComposeCompilationOwner { linked.compilation })
        }.code)
    }

    @Test fun `permit is live revocable provider bound and does not accept another root or copied metadata`() {
        val owned = fixture(listOf(state))
        val provider = DetektProvider()
        val permit = DetektLibraryPermit.prepare(provider, owned.input, DetektComposeCompilationOwner { owned.compilation })
        val authority = ExactDetektLibraryAuthority(permit)
        assertSame(permit, authority.authorize(owned.input))
        assertEquals("detekt_compose_binding_mismatch", assertFailsWith<ExecutionProblem> { permit.verify(DetektProvider(), owned.input) }.code)
        assertNull(authority.authorize(owned.input.copy(materializedRoot = temporary.resolve("other-source-root").toString())))
        permit.close()
        assertNull(authority.authorize(owned.input))
        assertEquals("detekt_compose_binding_mismatch", assertFailsWith<ExecutionProblem> { permit.verify(provider, owned.input) }.code)
    }

    @Test fun `owner resolution is mandatory on each verification and can revoke without altering request`() {
        val owned = fixture(listOf(state))
        var active = true
        var calls = 0
        val provider = DetektProvider()
        val owner = DetektComposeCompilationOwner { calls++; check(active); owned.compilation }
        val permit = DetektLibraryPermit.prepare(provider, owned.input, owner)
        permit.use {
            val previous = calls
            it.verify(provider, owned.input)
            assertTrue(calls >= previous + 2)
            active = false
            assertFailsWith<IllegalStateException> { it.verify(provider, owned.input) }
        }
    }

    @Test fun `retained caller containers cannot change a prepared owner compilation`() {
        val owned = fixture(listOf(state))
        val sourceSets = owned.compilation.sourceSetIds.toMutableList()
        val provider = DetektProvider()
        val owner = DetektComposeCompilationOwner { owned.compilation.copy(sourceSetIds = sourceSets) }
        val permit = DetektLibraryPermit.prepare(provider, owned.input, owner)
        permit.use {
            sourceSets += "new-actual-source-set"
            assertEquals("detekt_compose_owner_resolution_changed", assertFailsWith<ExecutionProblem> { it.verify(provider, owned.input) }.code)
        }
    }

    @Test fun `published pins and pure supported metadata cannot substitute for actual binary verification`() {
        val metadata = reviewedMetadata(eight)
        val assessed = DetektComposeSurfaces.assess(metadata.input, metadata.compilation, Digests.sha256("inert source review"))
        assertEquals(setOf("ktc.compose.pack.RememberContentMissing", "ktc.compose.pack.ContentSlotReused", state,
            "ktc.compose.pack.UnstableCollections"), assessed.rules.filterValues { it.completeApiSurface }.keys)
        assertEquals(setOf(remember, autoboxing), assessed.rules.filterValues { it.executableFacets && !it.completeApiSurface }.keys)
        assertEquals(setOf(lambda, mutableParam), assessed.rules.filterValues { !it.executableFacets }.keys)
        assertEquals(setOf(DetektComposeApiFamily.LIFECYCLE_EFFECTS, DetektComposeApiFamily.CIRCUIT_EFFECTS,
            DetektComposeApiFamily.PROJECT_KEYED_EFFECTS), assessed.rules.getValue(lambda).apiStates.filterValues {
            it == DetektComposeApiState.UNKNOWN
        }.keys)
        assertTrue(assessed.rules.getValue(remember).apiStates[DetektComposeApiFamily.PRIMITIVE_COLLECTION_STATE] == DetektComposeApiState.UNKNOWN)
        // No actual runtime binaries were written. Valid copied SOURCE/.module metadata is not authority.
        assertEquals("detekt_compose_dependency_bytes_changed", assertFailsWith<ExecutionProblem> {
            DetektLibraryPermit.prepare(DetektProvider(), metadata.input, DetektComposeCompilationOwner { metadata.compilation })
        }.code)
    }

    @Test fun `unknown versions wrong target missing atomic annotation or changed advertised bytes do not widen tuple`() {
        val metadata = reviewedMetadata(eight)
        val variants = listOf(
            metadata.compilation.copy(platform = "native"),
            metadata.compilation.copy(compilerVersion = "2.4.10"),
            metadata.compilation.copy(dependencies = metadata.compilation.dependencies.mapIndexed { index, artifact ->
                if (index == 0) artifact.copy(identity = artifact.identity.copy(coordinate = "androidx.compose.runtime:runtime-desktop:1.5.0")) else artifact
            }),
            metadata.compilation.copy(dependencies = metadata.compilation.dependencies.mapIndexed { index, artifact ->
                if (index == 0) artifact.copy(identity = artifact.identity.copy(digest = Digests.sha256("wrong published binary"))) else artifact
            }),
            metadata.compilation.copy(dependencies = metadata.compilation.dependencies.filterNot { DetektComposeLibraryPins.annotations.matches(it) }),
        )
        for (variant in variants) {
            val assessed = DetektComposeSurfaces.assess(metadata.input, variant, Digests.sha256("inert source review"))
            assertTrue(assessed.rules.values.all { !it.executableFacets && !it.completeApiSurface })
            assertTrue(assessed.rules.values.all { it.reason in setOf("detekt_compose_tuple_unsupported", "detekt_compose_library_missing") })
        }
    }

    @Test fun `scalar message facets do not authorize collection recommendations or indistinguishable lifecycle sites`() {
        val metadata = reviewedMetadata(eight)
        val assessed = DetektComposeSurfaces.assess(metadata.input, metadata.compilation, Digests.sha256("not execution"))
        for (name in listOf("derivedStateOf", "mutableStateOf", "mutableIntStateOf", "mutableFloatStateOf", "mutableDoubleStateOf", "mutableLongStateOf")) {
            assertTrue(DetektComposeSurfaces.allowsDiagnostic(remember, rememberMessage(name), assessed))
        }
        for (name in listOf("mutableIntListOf", "mutableFloatSetOf", "mutableLongIntMapOf", "unreviewedFactory")) {
            assertFalse(DetektComposeSurfaces.allowsDiagnostic(remember, rememberMessage(name), assessed))
        }
        for (type in listOf("Int", "Long", "Double", "Float")) {
            assertTrue(DetektComposeSurfaces.allowsDiagnostic(autoboxing, scalarMessage(type), assessed))
            assertFalse(DetektComposeSurfaces.allowsDiagnostic(autoboxing, scalarMessage(type) + "\n", assessed))
            assertFalse(DetektComposeSurfaces.allowsDiagnostic(autoboxing, scalarMessage(type).replace("StateOf", "ListOf"), assessed))
        }
        assertFalse(DetektComposeSurfaces.allowsDiagnostic(lambda, "runtime and lifecycle messages are indistinguishable", assessed))
        assertFalse(DetektComposeSurfaces.allowsDiagnostic(mutableParam, "generic and primitive holder messages are indistinguishable", assessed))
        assertFalse(assessed.rules.getValue(autoboxing).completeApiSurface)
    }

    @Test fun `unreviewed 35 rule surfaces remain explicit unknown not implied by runtime pin or catalog registration`() {
        val metadata = reviewedMetadata(DetektComposeSurfaces.ruleIds.sorted())
        val assessed = DetektComposeSurfaces.assess(metadata.input, metadata.compilation, Digests.sha256("not execution"))
        val unknown = assessed.rules.filterKeys { it !in eight }
        assertEquals(35, unknown.size)
        assertTrue(unknown.values.all { !it.executableFacets && !it.completeApiSurface &&
            it.apiStates == mapOf(DetektComposeApiFamily.UNREVIEWED_RULE_SURFACE to DetektComposeApiState.UNKNOWN) })
    }

    @Test fun `missing wrong or ambiguous immutable family withholds only immutable collection advice`() {
        // Pure metadata classification: the advertised JARs are absent and cannot mint permits.
        val metadata = reviewedMetadata(eight)
        val unstable = "ktc.compose.pack.UnstableCollections"
        val selected = metadata.compilation.dependencies.single { DetektComposeLibraryPins.immutableCollections.matches(it) }
        val runtimeArtifacts = metadata.compilation.dependencies.filterNot { DetektComposeLibraryPins.immutableCollections.matches(it) }
        fun assess(optional: List<DetektComposeArtifact>): DetektComposeApplicability {
            val compilation = metadata.compilation.copy(dependencies = runtimeArtifacts + optional)
            val input = metadata.input.copy(context = metadata.input.context.copy(classpath = compilation.dependencies.map { it.identity }))
            return DetektComposeSurfaces.assess(input, compilation, Digests.sha256("pure immutable family classification"))
        }
        val baseline = assess(listOf(selected))
        assertTrue(baseline.rules.getValue(unstable).completeApiSurface)
        assertTrue(baseline.rules.getValue(unstable).executableFacets)
        val unrelated = DetektComposeArtifact(ArtifactIdentity("example:unrelated:1", Digests.sha256("unrelated metadata"),
            temporary.resolve("absent-unrelated.jar").toString()), 18)
        assertEquals(baseline.rules, assess(listOf(selected, unrelated)).rules)
        assertEquals(baseline.rules, assess(listOf(unrelated, selected)).rules)
        val otherVersion = selected.copy(identity = selected.identity.copy(coordinate = "org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.7"))
        val commonVariant = selected.copy(identity = selected.identity.copy(coordinate = "org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.8"))
        val wrongDigest = selected.copy(identity = selected.identity.copy(digest = Digests.sha256("wrong immutable binary")))
        val wrongSize = selected.copy(size = selected.size + 1)
        val unavailable = listOf(
            "missing" to emptyList<DetektComposeArtifact>(),
            "other-version-only" to listOf(otherVersion),
            "common-variant-only" to listOf(commonVariant),
            "wrong-digest-only" to listOf(wrongDigest),
            "wrong-size-only" to listOf(wrongSize),
            "exact-then-other-version" to listOf(selected, otherVersion),
            "other-version-then-exact" to listOf(otherVersion, selected),
            "exact-then-common-variant" to listOf(selected, commonVariant),
            "common-variant-then-exact" to listOf(commonVariant, selected),
            "exact-then-wrong-digest" to listOf(selected, wrongDigest),
            "wrong-digest-then-exact" to listOf(wrongDigest, selected),
            "duplicate-exact" to listOf(selected, selected),
        )
        for ((label, optional) in unavailable) {
            val result = assess(optional)
            val state = result.rules.getValue(unstable)
            assertFalse(state.executableFacets, label)
            assertFalse(state.completeApiSurface, label)
            assertEquals("detekt_compose_api_surface_unknown", state.reason, label)
            assertEquals(setOf(DetektComposeApiFamily.IMMUTABLE_COLLECTIONS), state.apiStates.filterValues {
                it == DetektComposeApiState.UNKNOWN
            }.keys, label)
            // All other metadata stays identical: full3 runtime rules, partial2 and withheld2.
            assertEquals(baseline.rules.filterKeys { it != unstable }, result.rules.filterKeys { it != unstable }, label)
        }
    }

    private data class Owned(val input: AnalysisInput, val compilation: DetektComposeCompilation)

    private fun fixture(rules: List<String>, source: String = "fun sample() {}\n",
                        dependencies: List<DetektComposeArtifact> = emptyList()): Owned {
        val root = Files.createTempDirectory(temporary, "owned-sources-")
        Files.writeString(root.resolve("source.kt"), source)
        val files = listOf(SnapshotFile("source.kt", Digests.sha256(source), source.toByteArray().size.toLong()))
        val scope = RuleContext(target = "jvm", sourceSet = "main", module = ":owned", compilation = "main")
        val compilation = DetektComposeCompilation("owner-actual-main", scope, "jvm", listOf("owner-source-set-main"),
            files, "2.4.21", emptyList(), dependencies)
        val input = AnalysisInput(SnapshotManifest(Digests.sha256("authority unit snapshot"), "fixture", "worktree", files),
            root.toString(), AnalysisContext(scope = scope, compilerVersion = "2.4.21",
                classpath = dependencies.map { it.identity }, compilationFiles = files.map { it.path }), rules)
        return Owned(input, compilation)
    }

    private fun artifact(coordinate: String, name: String, bytes: String): DetektComposeArtifact {
        val path = temporary.resolve(name)
        Files.writeString(path, bytes)
        return DetektComposeArtifact(ArtifactIdentity(coordinate, Digests.sha256(bytes), path.toString()), bytes.toByteArray().size.toLong())
    }

    /** Pure classification input: deliberately NO matching runtime JAR exists, so never a grant. */
    private fun reviewedMetadata(rules: List<String>): Owned = fixture(rules, dependencies = listOf(
        DetektComposeLibraryPins.runtime, DetektComposeLibraryPins.annotations, DetektComposeLibraryPins.immutableCollections,
    ).mapIndexed { ordinal, pin ->
        DetektComposeArtifact(ArtifactIdentity(pin.coordinate, pin.digest, temporary.resolve("absent-runtime-$ordinal.jar").toString()), pin.size)
    })

    private fun rememberMessage(name: String) = """
        Using `$name` in a @Composable function without it being inside of a remember function.
        If you don't remember the state instance, a new state instance will be created when the function is recomposed.
        See https://mrmans0n.github.io/compose-rules/rules/#state-should-be-remembered-in-composables for more information.
    """.trimIndent()

    private fun scalarMessage(type: String) = """
        Using mutable${type}StateOf is recommended over mutableStateOf<$type>, as it uses the $type primitive directly which is more performant.
        See https://mrmans0n.github.io/compose-rules/rules/#use-mutablestateof-type-specific-variants-when-possible for more information.
    """.trimIndent()

    private fun assertUnqualified(result: ProviderResult) {
        assertEquals("partial", result.status)
        assertFalse(result.complete())
        assertTrue(result.coverage.all { !it.successful() && !it.implemented && !it.tested && !it.qualified && it.certificateId == null })
        assertTrue(result.findings.all { it.kind == "advisory" && it.certainty == "low" && it.fixHint == null })
    }
}

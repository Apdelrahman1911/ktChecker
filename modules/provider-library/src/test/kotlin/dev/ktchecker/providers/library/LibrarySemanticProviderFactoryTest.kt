package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import dev.ktchecker.semantic.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Runtime dispatch/ownership boundary CONTROLS only. Existing fixture helpers deliberately use
 * synthetic compiler/binary/policy receipts. No test below is project activation, real extraction,
 * ordinary Gradle test eligibility, CLI delivery or rule/tuple qualification evidence.
 */
class LibrarySemanticProviderFactoryTest {
    private fun route(factory: LibrarySemanticProviderFactory, id: String): AnalysisProvider =
        factory.providers.single { it.id == id }

    private fun partial(result: ProviderResult) {
        assertFalse(result.complete())
        assertTrue(result.coverage.all { !it.successful() && it.certificateId == null && !it.qualified && !it.implemented && !it.tested })
    }

    @Test fun rosterPreservesExistingIdentitiesAndIsImmutable() {
        LibrarySemanticProviderFactory().use { factory ->
            val existing = listOf(RunBlockingInSuspendProvider(), CoroutineRefinementProvider(), EmptyAssertFailsWithProvider())
            assertEquals(existing.map { Triple(it.id, it.version, it.supportedRuleIds) },
                factory.providers.map { Triple(it.id, it.version, it.supportedRuleIds) })
            assertFailsWith<UnsupportedOperationException> { (factory.providers as MutableList<AnalysisProvider>).clear() }
            factory.providers.forEach { provider ->
                assertFailsWith<UnsupportedOperationException> { (provider.supportedRuleIds as MutableSet<String>).clear() }
            }
        }
    }

    @Test fun missingRegistrationIsUnavailableNotNotApplicableOrClean() {
        LibraryRuleFixtures().use { f ->
            val model = f.model()
            LibrarySemanticProviderFactory().use { factory ->
                factory.providers.forEach { provider ->
                    val input = f.input(model, provider.supportedRuleIds.sorted())
                    val result = provider.analyze(input)
                    assertEquals("partial", result.status)
                    assertEquals(input.requiredRuleIds, result.coverage.map { it.ruleId })
                    assertTrue(result.findings.isEmpty())
                    assertTrue(result.coverage.all { it.state == "unavailable" && it.provenance == "not_executed" })
                    assertEquals(listOf("library_runtime_owner_registration_unavailable"), result.reasons)
                    partial(result)
                }
            }
        }
    }

    @Test fun registeredRunBlockingForwardsTheEntireExistingResultAndMandatoryCallbacks() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); val selection = f.selection(model)
            val expected = f.analyze(model, selection, input)
            val receipts = LibraryRuleFixtures.Receipts()
            val authorized = RunBlockingInSuspendProvider(model, selection).authorizationRequest(input)
            val permit = f.syntheticPermit(model, authorized, receipts)
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerRunBlocking(input, model, selection, ExactLibraryFactAuthority(permit)).use {
                    assertEquals(0, receipts.snapshots); assertEquals(0, receipts.reads)
                    val actual = route(factory, LIBRARY_PROVIDER_ID).analyze(input)
                    assertEquals(expected, actual)
                    assertEquals(1, actual.findings.size)
                    assertEquals(1, receipts.snapshots); assertEquals(1, receipts.extractions)
                    assertEquals(1, receipts.policies); assertEquals(1, receipts.reads)
                    assertEquals(selection.applicability.selectedSymbolIds, receipts.ownedSymbols)
                    partial(actual)
                }
            }
        }
    }

    @Test fun registeredRefinementForwardsTheOriginalRuntimeAndPolicyChecks() {
        LibraryRuleFixtures("suspend fun waiting() { java.lang.Thread.sleep(1L) }\n").use { f ->
            val c = CoroutineRefinementControls(f); val input = c.input(listOf(SLEEP_IN_COROUTINE)); val selection = c.sleep()
            val expected = c.analyze(selection, input); c.trace.clear()
            val authorization = CoroutineRefinementProvider(c.model, selection).authorizationRequest(input)
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerCoroutineRefinement(input, c.model, selection,
                    ExactCoroutineRefinementAuthority(c.permit(authorization))).use {
                    assertTrue(c.trace.isEmpty())
                    val actual = route(factory, COROUTINE_REFINEMENT_PROVIDER_ID).analyze(input)
                    assertEquals(expected, actual); assertEquals(1, actual.findings.size)
                    assertTrue(c.trace.containsAll(listOf("snapshot", "extraction", "runtime", "ownership", "policy", "source")))
                    partial(actual)
                }
            }
        }
    }

    @Test fun registeredEmptyAssertionForwardsActualSelectedTestPolicyWithoutInference() {
        EmptyAssertFailsWithControls().use { c ->
            val model = c.model(); val input = c.input(model); val selection = c.scenario(model).selection
            val expected = c.analyze(model, selection, input); c.trace.clear()
            val authorization = EmptyAssertFailsWithProvider(model, selection).authorizationRequest(input)
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerEmptyAssertion(input, model, selection,
                    ExactEmptyAssertionAuthority(c.permit(model, authorization))).use {
                    assertTrue(c.trace.isEmpty())
                    val actual = route(factory, EMPTY_ASSERTION_PROVIDER_ID).analyze(input)
                    assertEquals(expected, actual); assertEquals(1, actual.findings.size)
                    assertTrue(c.trace.containsAll(listOf("snapshot", "extraction", "artifact", "ownership", "policy", "read")))
                    partial(actual)
                }
            }
        }
    }

    @Test fun structuralRegistrationDoesNotGrantAnAuthorityAndFailedDispatchCannotReplay() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); var requests = 0
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerRunBlocking(input, model, f.selection(model), LibraryFactAuthority { requests++; null }).use {
                    assertEquals(0, requests)
                    val provider = route(factory, LIBRARY_PROVIDER_ID)
                    val first = provider.analyze(input)
                    assertTrue(first.findings.isEmpty()); assertEquals(listOf("library_extraction_not_authorized"), first.reasons)
                    assertEquals(1, requests)
                    val replay = provider.analyze(input)
                    assertEquals(listOf("library_runtime_owner_registration_unavailable"), replay.reasons)
                    assertEquals(1, requests); partial(first); partial(replay)
                }
            }
        }
    }

    @Test fun exactInputMutationsNeverBorrowOrConsumeTheOriginalRegistration() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); val selection = f.selection(model); var requests = 0
            val mutations = listOf(
                input.copy(materializedRoot = input.materializedRoot + "/other"),
                input.copy(snapshot = input.snapshot.copy(ownerId = "another-owner")),
                input.copy(snapshot = input.snapshot.copy(files = input.snapshot.files.reversed())),
                input.copy(context = input.context.copy(configurationDigest = Digests.sha256("other-configuration"))),
                input.copy(context = input.context.copy(classpath = input.context.classpath.reversed())),
                input.copy(context = input.context.copy(compilerArguments = input.context.compilerArguments.reversed())),
                input.copy(context = input.context.copy(scope = input.context.scope.copy(compilation = "other"))),
                input.copy(context = input.context.copy(compilationFiles = emptyList())),
                input.copy(context = input.context.copy(policies = mapOf("changed" to "policy"))),
                input.copy(outputRoot = input.materializedRoot + "/other-output"),
                input.copy(baselineSnapshotId = Digests.sha256("other-baseline")),
                input.copy(changedPaths = listOf(f.source.path)),
                input.copy(requiredRuleIds = listOf(RUNBLOCKING_IN_SUSPEND, RUNBLOCKING_IN_SUSPEND)),
                input.copy(requiredRuleIds = listOf(SLEEP_IN_COROUTINE)),
            )
            assertTrue(mutations.all { it != input })
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerRunBlocking(input, model, selection, LibraryFactAuthority { request ->
                    requests++; f.syntheticPermit(model, request)
                }).use {
                    val provider = route(factory, LIBRARY_PROVIDER_ID)
                    mutations.forEach { changed ->
                        val result = provider.analyze(changed)
                        assertTrue(result.findings.isEmpty()); partial(result)
                    }
                    assertEquals(0, requests)
                    assertEquals(1, provider.analyze(input).findings.size)
                    assertEquals(1, requests)
                }
            }
        }
    }

    @Test fun duplicatePendingRegistrationCannotReplaceTheOriginalOwnerAuthority() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); val selection = f.selection(model); var calls = 0
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerRunBlocking(input, model, selection, LibraryFactAuthority { request ->
                    calls++; f.syntheticPermit(model, request)
                }).use {
                    val problem = assertFailsWith<IllegalStateException> {
                        factory.registerRunBlocking(input, model, selection, LibraryFactAuthority.DENY)
                    }
                    assertEquals("library_runtime_registration_already_pending", problem.message)
                    assertEquals(0, calls)
                    assertEquals(1, route(factory, LIBRARY_PROVIDER_ID).analyze(input).findings.size)
                    assertEquals(1, calls)
                }
            }
        }
    }

    @Test fun pendingCapacityRejectsNewHandoffsWithoutEvictingOrReducingRuleScope() {
        assertFailsWith<IllegalArgumentException> { LibrarySemanticProviderFactory(0) }
        assertFailsWith<IllegalArgumentException> { LibrarySemanticProviderFactory(257) }
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); val selection = f.selection(model)
            LibrarySemanticProviderFactory(1).use { factory ->
                val first = factory.registerRunBlocking(input, model, selection, LibraryFactAuthority.DENY)
                val changed = input.copy(changedPaths = listOf(f.source.path))
                assertEquals("library_runtime_pending_limit_reached", assertFailsWith<IllegalStateException> {
                    factory.registerRunBlocking(changed, model, selection, LibraryFactAuthority.DENY)
                }.message)
                first.close()
                factory.registerRunBlocking(changed, model, selection, LibraryFactAuthority.DENY).close()
                assertEquals(4, factory.providers.flatMap { it.supportedRuleIds }.size)
            }
        }
    }

    @Test fun registrationCloseIsIdempotentAndRevokesOnlyUndispatchedWork() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); var calls = 0
            LibrarySemanticProviderFactory().use { factory ->
                val registration = factory.registerRunBlocking(input, model, f.selection(model), LibraryFactAuthority { calls++; null })
                registration.close(); registration.close()
                val result = route(factory, LIBRARY_PROVIDER_ID).analyze(input)
                assertEquals(listOf("library_runtime_owner_registration_unavailable"), result.reasons)
                assertEquals(0, calls); partial(result)
            }
        }
    }

    @Test fun closingConsumedOldHandleCannotRevokeAFreshSameKeyRegistration() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); val selection = f.selection(model)
            LibrarySemanticProviderFactory().use { factory ->
                val authority = LibraryFactAuthority { request -> f.syntheticPermit(model, request) }
                val old = factory.registerRunBlocking(input, model, selection, authority)
                val provider = route(factory, LIBRARY_PROVIDER_ID)
                assertEquals(1, provider.analyze(input).findings.size)
                factory.registerRunBlocking(input, model, selection, authority).use {
                    old.close()
                    assertEquals(1, provider.analyze(input).findings.size)
                }
            }
        }
    }

    @Test fun factoryCloseRevokesPendingAndRejectsLateOwnerEnrollment() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); val selection = f.selection(model); var calls = 0
            val factory = LibrarySemanticProviderFactory()
            val registration = factory.registerRunBlocking(input, model, selection, LibraryFactAuthority { calls++; null })
            factory.close(); factory.close(); registration.close()
            val result = route(factory, LIBRARY_PROVIDER_ID).analyze(input)
            assertEquals(listOf("library_runtime_owner_registration_unavailable"), result.reasons)
            assertEquals(0, calls); partial(result)
            assertEquals("library_runtime_closed", assertFailsWith<IllegalStateException> {
                factory.registerRunBlocking(input, model, selection, LibraryFactAuthority.DENY)
            }.message)
        }
    }

    @Test fun registrationFreezesCallerOwnedFactsPoliciesAndInputVectors() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val expectedInput = f.input(model); val original = f.selection(model)
            val rules = expectedInput.requiredRuleIds.toMutableList(); val paths = mutableListOf<String>()
            val dependencies = original.request.dependencyArtifacts.toMutableList()
            val symbols = original.shards.single().symbols.toMutableList()
            val shards = mutableListOf(original.shards.single().copy(symbols = symbols))
            val selectedIds = original.applicability.selectedSymbolIds.toMutableList()
            val selection = original.copy(request = original.request.copy(dependencyArtifacts = dependencies), shards = shards,
                applicability = original.applicability.copy(selectedSymbolIds = selectedIds))
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerRunBlocking(expectedInput.copy(requiredRuleIds = rules, changedPaths = paths), model, selection,
                    LibraryFactAuthority { request ->
                        assertEquals(expectedInput, request.input)
                        assertEquals(original.applicability, request.applicability)
                        f.syntheticPermit(model, request)
                    }).use {
                    rules.clear(); paths += "changed-after-registration.kt"; dependencies.clear(); symbols.clear(); shards.clear(); selectedIds.clear()
                    assertEquals(1, route(factory, LIBRARY_PROVIDER_ID).analyze(expectedInput).findings.size)
                }
            }
        }
    }

    @Test fun cancellationBeforeDispatchDoesNotConsumeOrApproveTheRegistration() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); var calls = 0
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerRunBlocking(input, model, f.selection(model), LibraryFactAuthority { request ->
                    calls++; f.syntheticPermit(model, request)
                }).use {
                    val provider = route(factory, LIBRARY_PROVIDER_ID)
                    val cancelled = provider.analyze(input, CancellationToken { true })
                    assertEquals("cancelled", cancelled.status); assertEquals(0, calls); partial(cancelled)
                    assertEquals(1, provider.analyze(input).findings.size); assertEquals(1, calls)
                }
            }
        }
    }

    @Test fun callerCancellationPredicateCannotRebindTheFrozenDispatchInput() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); val rules = input.requiredRuleIds.toMutableList()
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerRunBlocking(input, model, f.selection(model), LibraryFactAuthority { request ->
                    f.syntheticPermit(model, request)
                }).use {
                    val result = route(factory, LIBRARY_PROVIDER_ID).analyze(input.copy(requiredRuleIds = rules),
                        CancellationToken { rules.clear(); false })
                    assertEquals(1, result.findings.size); assertEquals(input.requiredRuleIds, result.coverage.map { it.ruleId })
                    partial(result)
                }
            }
        }
    }

    @Test fun originalWorkerCancellationIsForwardedAfterDispatchAndNeverReplacedByNone() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); val cancelled = AtomicBoolean(); val receipts = LibraryRuleFixtures.Receipts()
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerRunBlocking(input, model, f.selection(model), LibraryFactAuthority { request ->
                    f.syntheticPermit(model, request, receipts, afterExtraction = { cancelled.set(true) })
                }).use {
                    val result = route(factory, LIBRARY_PROVIDER_ID).analyze(input, CancellationToken { cancelled.get() })
                    assertEquals("cancelled", result.status); assertTrue(result.findings.isEmpty())
                    assertEquals(1, receipts.extractions); assertEquals(0, receipts.reads); partial(result)
                    assertEquals(listOf("library_runtime_owner_registration_unavailable"),
                        route(factory, LIBRARY_PROVIDER_ID).analyze(input).reasons)
                }
            }
        }
    }

    @Test fun concurrentDispatchHasOneWinnerAndOneActualAuthorityConsultation() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); val calls = AtomicInteger(); val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                LibrarySemanticProviderFactory().use { factory ->
                    factory.registerRunBlocking(input, model, f.selection(model), LibraryFactAuthority { request ->
                        calls.incrementAndGet(); f.syntheticPermit(model, request)
                    }).use {
                        val provider = route(factory, LIBRARY_PROVIDER_ID)
                        val tasks = List(2) { executor.submit<ProviderResult> { check(start.await(10, TimeUnit.SECONDS)); provider.analyze(input) } }
                        start.countDown()
                        val results = tasks.map { it.get(10, TimeUnit.SECONDS) }
                        assertEquals(1, calls.get()); assertEquals(1, results.count { it.findings.size == 1 })
                        assertEquals(1, results.count { it.reasons == listOf("library_runtime_owner_registration_unavailable") })
                        results.forEach(::partial)
                    }
                }
            } finally { start.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun matchingSerializedModelMetadataCannotReplaceTheOriginalPermitModelIdentity() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val anotherModel = f.model(); val input = f.input(model); val selection = f.selection(model)
            assertNotSame(model, anotherModel); assertEquals(model.identity, anotherModel.identity)
            val request = RunBlockingInSuspendProvider(model, selection).authorizationRequest(input)
            val receipts = LibraryRuleFixtures.Receipts(); val permit = f.syntheticPermit(model, request, receipts)
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerRunBlocking(input, anotherModel, selection, ExactLibraryFactAuthority(permit)).use {
                    val result = route(factory, LIBRARY_PROVIDER_ID).analyze(input)
                    assertEquals(listOf("library_fact_authorization_mismatch_or_replay"), result.reasons)
                    assertTrue(result.findings.isEmpty()); assertEquals(0, receipts.snapshots); assertEquals(0, receipts.reads)
                    partial(result)
                }
            }
        }
    }

    @Test fun missingOwnerTestMembershipIsNotInferredFromTestAnnotationOrSourceSetName() {
        EmptyAssertFailsWithControls().use { c ->
            val model = c.model(); val input = c.input(model); val original = c.scenario(model).selection
            val selection = original.copy(applicability = original.applicability.copy(testEntries = emptyList()))
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerEmptyAssertion(input, model, selection, EmptyAssertionAuthority { request -> c.permit(model, request) }).use {
                    val result = route(factory, EMPTY_ASSERTION_PROVIDER_ID).analyze(input)
                    assertTrue(result.findings.isEmpty())
                    assertTrue("empty_assertion_required_test_contract_unavailable" in result.reasons)
                    assertTrue("policy" in c.trace); partial(result)
                }
            }
        }
    }

    @Test fun registrationRetainsExistingBindingRejectionsAndDoesNotEnrollMalformedSelections() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); val selection = f.selection(model)
            LibrarySemanticProviderFactory().use { factory ->
                assertFailsWith<IllegalArgumentException> {
                    factory.registerRunBlocking(input, model, selection.copy(compilerExitCode = 1), LibraryFactAuthority.DENY)
                }
                assertFailsWith<IllegalArgumentException> {
                    factory.registerRunBlocking(input.copy(requiredRuleIds = emptyList()), model, selection, LibraryFactAuthority.DENY)
                }
                assertFailsWith<IllegalArgumentException> {
                    factory.registerRunBlocking(input.copy(context = input.context.copy(classpath = input.context.classpath.reversed())),
                        model, selection, LibraryFactAuthority.DENY)
                }
                val result = route(factory, LIBRARY_PROVIDER_ID).analyze(input)
                assertEquals(listOf("library_runtime_owner_registration_unavailable"), result.reasons); partial(result)
            }
        }
    }

    @Test fun factoryCloseDuringDispatchDoesNotInventCancellationOrChangeProviderResults() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model)
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerRunBlocking(input, model, f.selection(model), LibraryFactAuthority { request ->
                    factory.close() // Dispatch already owns this analysis; the caller's token is still NONE.
                    f.syntheticPermit(model, request)
                }).use {
                    val provider = route(factory, LIBRARY_PROVIDER_ID)
                    val result = provider.analyze(input)
                    assertEquals(1, result.findings.size); partial(result)
                    assertEquals(listOf("library_runtime_owner_registration_unavailable"), provider.analyze(input).reasons)
                }
            }
        }
    }

    @Test fun explicitTestPolicyVectorIsFrozenAtRegistrationNotRediscoveredAtDispatch() {
        EmptyAssertFailsWithControls().use { c ->
            val model = c.model(); val input = c.input(model); val original = c.scenario(model).selection
            val entries = original.applicability.testEntries.toMutableList()
            val selection = original.copy(applicability = original.applicability.copy(testEntries = entries))
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerEmptyAssertion(input, model, selection, EmptyAssertionAuthority { request ->
                    assertEquals(original.applicability.testEntries, request.applicability.testEntries)
                    c.permit(model, request)
                }).use {
                    entries.clear()
                    assertEquals(1, route(factory, EMPTY_ASSERTION_PROVIDER_ID).analyze(input).findings.size)
                }
            }
        }
    }

    @Test fun multiRuleChildVectorRetainsItsExactOrderAndSubset() {
        LibraryRuleFixtures("suspend fun waiting() { java.lang.Thread.sleep(1L) }\n").use { f ->
            val c = CoroutineRefinementControls(f); val input = c.input(); var calls = 0
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerCoroutineRefinement(input, c.model, c.sleep(), CoroutineRefinementAuthority { request ->
                    calls++; c.permit(request)
                }).use {
                    val provider = route(factory, COROUTINE_REFINEMENT_PROVIDER_ID)
                    listOf(input.copy(requiredRuleIds = input.requiredRuleIds.reversed()),
                        input.copy(requiredRuleIds = listOf(SLEEP_IN_COROUTINE))).forEach { changed ->
                        val result = provider.analyze(changed)
                        assertEquals(listOf("library_runtime_owner_registration_unavailable"), result.reasons); partial(result)
                    }
                    assertEquals(0, calls)
                    val result = provider.analyze(input)
                    assertEquals(input.requiredRuleIds, result.coverage.map { it.ruleId })
                    assertEquals(1, result.findings.size); assertEquals(1, calls); partial(result)
                }
            }
        }
    }

    @Test fun oversizedScopeCannotAmplifyBoundedRulesIntoUnboundedCoverage() {
        LibraryRuleFixtures().use { f ->
            val model = f.model(); val input = f.input(model); val selection = f.selection(model)
            var authorityCalls = 0; var cancellationCalls = 0
            LibrarySemanticProviderFactory().use { factory ->
                factory.registerRunBlocking(input, model, selection, LibraryFactAuthority { request ->
                    authorityCalls++; f.syntheticPermit(model, request)
                }).use {
                    for (length in listOf(4097, 1_000_000)) {
                        val oversized = "s".repeat(length)
                        val scope = input.context.scope
                        val scopes = listOf(scope.copy(target = oversized), scope.copy(sourceSet = oversized),
                            scope.copy(module = oversized), scope.copy(compilation = oversized))
                        scopes.forEach { changedScope ->
                            val malformed = input.copy(context = input.context.copy(scope = changedScope),
                                requiredRuleIds = List(4096) { "unsupported.$it" })
                            // This fits the old aggregate input budget: only the per-field guard
                            // prevents multiplying a large scope by the permitted number of rows.
                            LibraryTextBudget().input(malformed)
                            factory.providers.forEach { provider ->
                                val result = provider.analyze(malformed, CancellationToken { cancellationCalls++; false })
                                assertEquals(listOf("library_runtime_input_invalid_or_unbounded"), result.reasons)
                                assertTrue(result.coverage.isEmpty()); assertTrue(result.findings.isEmpty()); partial(result)
                            }
                            assertFailsWith<IllegalArgumentException> {
                                factory.registerRunBlocking(malformed.copy(requiredRuleIds = input.requiredRuleIds),
                                    model, selection, LibraryFactAuthority.DENY)
                            }
                        }
                    }
                    assertEquals(0, cancellationCalls); assertEquals(0, authorityCalls)
                    val valid = route(factory, LIBRARY_PROVIDER_ID).analyze(input)
                    assertEquals(1, valid.findings.size); assertEquals(1, authorityCalls); partial(valid)
                }
            }
        }
    }

    @Test fun oversizedLookupCannotExpandAnUnboundedCoverageVector() {
        LibraryRuleFixtures().use { f ->
            val input = f.input(f.model()).copy(requiredRuleIds = List(4097) { RUNBLOCKING_IN_SUSPEND })
            LibrarySemanticProviderFactory().use { factory ->
                val result = route(factory, LIBRARY_PROVIDER_ID).analyze(input)
                assertEquals(listOf("library_runtime_input_invalid_or_unbounded"), result.reasons)
                assertTrue(result.coverage.isEmpty()); assertTrue(result.findings.isEmpty()); partial(result)
            }
        }
    }
}

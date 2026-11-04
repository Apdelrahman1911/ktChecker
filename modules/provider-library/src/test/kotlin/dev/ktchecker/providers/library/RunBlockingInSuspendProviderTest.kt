package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.ModelIssue
import dev.ktchecker.semantic.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Synthetic boundary/algorithm controls, not genuine library/compiler or release qualification. */
class RunBlockingInSuspendProviderTest {
    private inline fun fixture(text: String = "suspend fun owner() { runBlocking { 1 } }\n",
                               block: (LibraryRuleFixtures) -> Unit) = LibraryRuleFixtures(text).use(block)

    private fun noncertifying(result: ProviderResult) {
        assertEquals("partial", result.status)
        assertFalse(result.complete())
        assertTrue(result.coverage.all { it.certificateId == null && !it.successful() && !it.implemented && !it.tested && !it.qualified })
    }

    @Test fun direct_suspend_function_is_an_advisory_not_qualified_coverage() = fixture { f ->
        val model = f.model()
        val receipts = LibraryRuleFixtures.Receipts()
        val result = f.analyze(model, f.selection(model), receipts = receipts)
        noncertifying(result)
        val finding = result.findings.single()
        assertEquals(RUNBLOCKING_IN_SUSPEND, finding.ruleId)
        assertEquals("medium", finding.certainty)
        assertEquals("probable_defect", finding.kind)
        assertContains(finding.diagnosis, "blocks its current thread")
        assertTrue(finding.fixHint == null)
        assertEquals(f.span("runBlocking { 1 }").startColumn, finding.location.column)
        assertEquals(f.source.digest, finding.location.fileDigest)
        assertEquals(1, receipts.snapshots)
        assertEquals(1, receipts.extractions)
        assertEquals(4, receipts.artifacts)
        assertEquals(listOf(f.target().id), receipts.ownedSymbols)
        assertEquals(1, receipts.policies)
        assertEquals(1, receipts.reads)
    }

    @Test fun directly_suspend_lambda_is_supported_without_generic_owner_id_conflation() =
        fixture("val body: suspend () -> Unit = { runBlocking { 1 } }\n") { f ->
            val model = f.model()
            val owner = f.owner("anonymous", "lambda", local = true, source = f.span("{ runBlocking { 1 } }"))
            val target = f.target()
            assertNotEquals(target.id, target.returnType!!.parameterOwner)
            val result = f.analyze(model, f.selection(model, listOf(owner), listOf(target)))
            noncertifying(result)
            assertEquals(owner.id, result.findings.single().symbolId)
        }

    @Test fun local_suspend_function_uses_its_source_bound_local_identity() = fixture { f ->
        val model = f.model()
        val owner = f.owner(local = true)
        val result = f.analyze(model, f.selection(model, listOf(owner)))
        assertEquals(owner.id, result.findings.single().symbolId)
        noncertifying(result)
    }

    @Test fun complete_shaped_source_validated_facts_do_not_authorize_observations() = fixture { f ->
        val model = f.model()
        val selection = f.selection(model)
        val bundle = SemanticFactsValidation.assemble(selection.request, selection.shards, 0, sourceBytes = { f.sourceBytes })
        assertTrue(bundle.completeForSupportedVocabulary)
        assertFalse(bundle.tupleQualified)
        val result = RunBlockingInSuspendProvider(model, selection).analyze(f.input(model))
        assertTrue(result.findings.isEmpty())
        assertEquals("library_extraction_not_authorized", result.coverage.single().reason)
        noncertifying(result)
    }

    @Test fun missing_model_and_missing_fact_selection_are_unavailable_not_clean() = fixture { f ->
        val model = f.model()
        val input = f.input(model)
        listOf(RunBlockingInSuspendProvider(), RunBlockingInSuspendProvider(model)).forEach { provider ->
            val result = provider.analyze(input)
            assertTrue(result.findings.isEmpty())
            assertEquals("unavailable", result.coverage.single().state)
            noncertifying(result)
        }
    }

    @Test fun serialized_authorization_request_is_not_a_typed_permit() = fixture { f ->
        val model = f.model()
        val selection = f.selection(model)
        val provider = RunBlockingInSuspendProvider(model, selection)
        val metadata = provider.authorizationRequest(f.input(model))
        val decoded = ContractJson.decodeFromString<LibraryFactAuthorizationRequest>(ContractJson.encodeToString(metadata))
        assertEquals(metadata, decoded)
        assertTrue(provider.analyze(decoded.input).findings.isEmpty())
    }

    @Test fun nonsuspend_synchronous_entrypoint_is_not_promoted_to_suspend() =
        fixture("fun main() { runBlocking { 1 } }\n") { f ->
            val model = f.model()
            val result = f.analyze(model, f.selection(model, listOf(f.owner("main", suspend = false))))
            assertTrue(result.findings.isEmpty())
            assertEquals("unqualified", result.coverage.single().state)
            noncertifying(result)
        }

    @Test fun nested_nonsuspend_callback_does_not_inherit_lexical_suspend_owner() =
        fixture("suspend fun owner() { val callback = { runBlocking { 1 } } }\n") { f ->
            val model = f.model()
            val outer = f.owner()
            val callback = f.owner("callback", "lambda", suspend = false, local = true,
                source = f.span("{ runBlocking { 1 } }"))
            val target = f.target()
            val result = f.analyze(model, f.selection(model, listOf(outer, callback), listOf(target),
                listOf(f.call(target, callback))))
            assertTrue(result.findings.isEmpty())
            noncertifying(result)
        }

    @Test fun nested_nonsuspend_local_function_does_not_inherit_lexical_suspend_owner() =
        fixture("suspend fun owner() { fun callback() { runBlocking { 1 } } }\n") { f ->
            val model = f.model()
            val outer = f.owner()
            val callback = f.owner("callback", suspend = false, local = true,
                source = f.span("fun callback() { runBlocking { 1 } }"))
            val target = f.target()
            val result = f.analyze(model, f.selection(model, listOf(outer, callback), listOf(target),
                listOf(f.call(target, callback))))
            assertTrue(result.findings.isEmpty())
            noncertifying(result)
        }

    @Test fun intervening_property_class_and_value_parameter_owners_abstain_without_ancestor_climb() =
        fixture("suspend fun owner() { val value = runBlocking { 1 } }\n") { f ->
            val model = f.model()
            val outer = f.owner()
            listOf("property", "class", "value_parameter", "getter", "setter").forEach { kind ->
                val intervening = f.owner("intervening", kind, source = f.span("val value = runBlocking { 1 }"))
                val target = f.target()
                val result = f.analyze(model, f.selection(model, listOf(outer, intervening), listOf(target),
                    listOf(f.call(target, intervening))))
                assertTrue(result.findings.isEmpty())
                assertContains(result.reasons, "library_runblocking_direct_function_owner_unavailable")
                noncertifying(result)
            }
        }

    @Test fun absent_or_noncaptured_containing_owner_never_yields_a_finding() = fixture { f ->
        val model = f.model()
        val target = f.target()
        val selection = f.selection(model, calls = listOf(f.call(target, null)))
        val result = f.analyze(model, selection)
        assertTrue(result.findings.isEmpty())
        assertContains(result.reasons, "library_runblocking_direct_function_owner_unavailable")
        val uncaptured = f.owner().copy(source = null)
        val rebound = uncaptured.copy(id = resolvedSymbolIdentity(uncaptured))
        val other = f.analyze(model, f.selection(model, listOf(rebound), listOf(target)))
        assertTrue(other.findings.isEmpty())
        noncertifying(other)
        val captured = f.selection(model)
        val notDeclared = captured.copy(shards = captured.shards.map { it.copy(declarations = emptyList()) })
        val missingCapture = f.analyze(model, notDeclared)
        assertTrue(missingCapture.findings.isEmpty())
        assertContains(missingCapture.reasons, "library_runblocking_captured_direct_owner_unavailable")
        noncertifying(missingCapture)
    }

    @Test fun synthetic_call_or_owner_ranges_are_not_real_source_observations() = fixture { f ->
        val model = f.model()
        val target = f.target()
        val owner = f.owner()
        val syntheticCall = f.call(target, owner).let { it.copy(source = it.source.copy(synthetic = true)) }
        val syntheticOwner = owner.copy(source = requireNotNull(owner.source).copy(synthetic = true))
        listOf(
            f.selection(model, listOf(owner), listOf(target), listOf(syntheticCall)),
            f.selection(model, listOf(syntheticOwner), listOf(target)),
        ).forEach { selection ->
            val result = f.analyze(model, selection)
            assertTrue(result.findings.isEmpty())
            assertContains(result.reasons, "library_runblocking_captured_direct_owner_unavailable")
            noncertifying(result)
        }
    }

    @Test fun callable_reference_is_not_an_invocation() = fixture("suspend fun owner() { val reference = ::runBlocking }\n") { f ->
        val model = f.model()
        val owner = f.owner()
        val target = f.target()
        val reference = f.call(target, owner, "::runBlocking", "callable_reference")
        val result = f.analyze(model, f.selection(model, listOf(owner), listOf(target), listOf(reference)))
        assertTrue(result.findings.isEmpty())
        noncertifying(result)
    }

    @Test fun import_alias_spelling_does_not_change_the_resolved_target_match() =
        fixture("suspend fun owner() { blockingAlias { 1 } }\n") { f ->
            val model = f.model()
            val owner = f.owner()
            val target = f.target()
            val result = f.analyze(model, f.selection(model, listOf(owner), listOf(target),
                listOf(f.call(target, owner, "blockingAlias { 1 }"))))
            assertEquals(1, result.findings.size)
            noncertifying(result)
        }

    @Test fun same_named_project_api_is_not_the_selected_binary_callable() = fixture { f ->
        val model = f.model()
        val actual = f.target()
        val shadow = f.target { it.copy(origin = "Source", source = f.span(0, f.text.trimEnd().length)) }
        val owner = f.owner()
        val base = f.selection(model, listOf(owner), listOf(actual, shadow), listOf(f.call(shadow, owner)))
        val selected = base.copy(applicability = base.applicability.copy(selectedSymbolIds = listOf(actual.id)))
        val result = f.analyze(model, selected)
        assertTrue(result.findings.isEmpty())
        noncertifying(result)
    }

    @Test fun selected_source_shadow_and_unsupported_overload_shapes_are_rejected_before_authorization() = fixture { f ->
        val model = f.model()
        val transformations: List<(SymbolFact) -> SymbolFact> = listOf(
            { it.copy(origin = "Source", source = f.span(0, f.text.trimEnd().length)) },
            { it.copy(receiverType = LibraryRuleFixtures.plainClass("kotlinx.coroutines.CoroutineScope")) },
            { it.copy(typeParameterCount = 2) },
            { it.copy(flags = it.flags + "suspend") },
            { it.copy(returnType = requireNotNull(it.returnType).copy(nullable = true)) },
            { it.copy(parameters = it.parameters.map { p -> if (p.index == 0) p.copy(type = LibraryRuleFixtures.plainClass("kotlin.String")) else p }) },
            { it.copy(parameters = it.parameters.map { p -> if (p.index == 1) p.copy(name = "action") else p }) },
        )
        transformations.forEach { transform ->
            val target = f.target(transform)
            var authorizations = 0
            val result = RunBlockingInSuspendProvider(model, f.selection(model, targets = listOf(target)),
                LibraryFactAuthority { authorizations++; null }).analyze(f.input(model))
            assertEquals(0, authorizations)
            assertTrue(result.findings.isEmpty())
            assertEquals("library_runblocking_signature_unsupported", result.coverage.single().reason)
            noncertifying(result)
        }
    }

    @Test fun unselected_binary_overload_is_an_explicit_gap_not_name_based_detection() = fixture { f ->
        val model = f.model()
        val selected = f.target()
        val other = f.target { it.copy(compilerModule = "SYNTHETIC-other-binary") }
        val owner = f.owner()
        val base = f.selection(model, listOf(owner), listOf(selected, other), listOf(f.call(other, owner)))
        val result = f.analyze(model, base.copy(applicability = base.applicability.copy(selectedSymbolIds = listOf(selected.id))))
        assertTrue(result.findings.isEmpty())
        assertContains(result.reasons, "library_runblocking_overload_not_selected")
        noncertifying(result)
    }

    @Test fun selected_callable_absent_from_captured_symbol_facts_is_not_vacuously_validated() = fixture { f ->
        val model = f.model()
        val original = f.selection(model)
        val noTarget = original.copy(shards = original.shards.map { shard ->
            shard.copy(symbols = shard.symbols.filter { it.id !in original.applicability.selectedSymbolIds }, calls = emptyList())
        })
        var authorizations = 0
        val result = RunBlockingInSuspendProvider(model, noTarget,
            LibraryFactAuthority { authorizations++; null }).analyze(f.input(model))
        assertEquals(0, authorizations)
        assertEquals("library_selected_callable_facts_missing", result.coverage.single().reason)
        assertTrue(result.findings.isEmpty())
        noncertifying(result)
    }

    @Test fun same_module_fqn_shadow_binary_without_artifact_ownership_receipt_is_unavailable() = fixture { f ->
        val model = f.model()
        val selection = f.selection(model)
        val input = f.input(model)
        val reviewed = RunBlockingInSuspendProvider(model, selection).authorizationRequest(input)
        val receipts = LibraryRuleFixtures.Receipts()
        val permit = f.syntheticPermit(model, reviewed, receipts,
            afterOwnership = { error("SYNTHETIC ownership receipt absent for classpath shadow") })
        val result = RunBlockingInSuspendProvider(model, selection, ExactLibraryFactAuthority(permit)).analyze(input)
        assertTrue(result.findings.isEmpty())
        assertEquals("unavailable", result.coverage.single().state)
        assertEquals(0, receipts.policies)
        assertEquals(0, receipts.reads)
        noncertifying(result)
    }

    @Test fun source_bound_interop_exemption_preserves_independent_unsuppressed_owner() =
        fixture("suspend fun owner() { runBlocking { 1 } }\nsuspend fun other() { runBlocking { 2 } }\n") { f ->
            val model = f.model()
            val owner = f.owner(source = f.span("suspend fun owner() { runBlocking { 1 } }"))
            val other = f.owner("other", source = f.span("suspend fun other() { runBlocking { 2 } }"))
            val target = f.target()
            val selection = f.selection(model, listOf(owner, other), listOf(target),
                listOf(f.call(target, owner), f.call(target, other, "runBlocking { 2 }")),
                listOf(BlockingInteropExemption(owner.id, owner.source!!)))
            val result = f.analyze(model, selection)
            assertEquals(other.id, result.findings.single().symbolId)
            noncertifying(result)
        }

    @Test fun nonlocal_id_cannot_reuse_an_exemption_for_changed_captured_body() = fixture { old ->
        fixture("suspend fun owner() { runBlocking { 1 }; println(2) }\n") { fresh ->
            val model = fresh.model()
            val oldOwner = old.owner()
            val owner = fresh.owner()
            assertEquals(oldOwner.id, owner.id)
            assertNotEquals(oldOwner.source!!.digest, owner.source!!.digest)
            val selection = fresh.selection(model, exemptions = listOf(BlockingInteropExemption(oldOwner.id, oldOwner.source!!)))
            val result = RunBlockingInSuspendProvider(model, selection).analyze(fresh.input(model))
            assertEquals("library_blocking_interop_exemption_not_source_bound", result.coverage.single().reason)
            assertTrue(result.findings.isEmpty())
            noncertifying(result)
        }
    }

    @Test fun missing_owner_policy_receipt_does_not_silently_allow_or_suppress_a_call() = fixture { f ->
        val model = f.model()
        val selection = f.selection(model)
        val input = f.input(model)
        val reviewed = RunBlockingInSuspendProvider(model, selection).authorizationRequest(input)
        val receipts = LibraryRuleFixtures.Receipts()
        val permit = f.syntheticPermit(model, reviewed, receipts, afterPolicy = { error("SYNTHETIC policy not authorized") })
        val result = RunBlockingInSuspendProvider(model, selection, ExactLibraryFactAuthority(permit)).analyze(input)
        assertTrue(result.findings.isEmpty())
        assertEquals(0, receipts.reads)
        noncertifying(result)
    }

    @Test fun verified_model_gap_snapshot_scope_and_configuration_changes_are_not_certified() = fixture { f ->
        val complete = f.model()
        val selection = f.selection(complete)
        val input = f.input(complete)
        val incomplete = f.model(listOf(ModelIssue("synthetic_unknown_input", detail = "Deliberately incomplete owner model")))
        assertTrue(RunBlockingInSuspendProvider(incomplete, selection).analyze(f.input(incomplete)).findings.isEmpty())
        listOf(
            input.copy(snapshot = input.snapshot.copy(worktreeId = "foreign")),
            input.copy(context = input.context.copy(configurationDigest = Digests.sha256("foreign-policy"))),
            input.copy(context = input.context.copy(scope = input.context.scope.copy(compilation = "other"))),
        ).forEach { changed ->
            var authorizations = 0
            val result = RunBlockingInSuspendProvider(complete, selection,
                LibraryFactAuthority { authorizations++; null }).analyze(changed)
            assertEquals(0, authorizations)
            assertTrue(result.findings.isEmpty())
            noncertifying(result)
        }
    }

    @Test fun wrong_actual_model_object_cannot_consume_an_otherwise_matching_permit() = fixture { f ->
        val model = f.model()
        val another = f.model()
        val selection = f.selection(model)
        val input = f.input(model)
        val reviewed = RunBlockingInSuspendProvider(model, selection).authorizationRequest(input)
        val receipts = LibraryRuleFixtures.Receipts()
        val permit = f.syntheticPermit(model, reviewed, receipts)
        val result = RunBlockingInSuspendProvider(another, selection, ExactLibraryFactAuthority(permit)).analyze(input)
        assertTrue(result.findings.isEmpty())
        assertEquals("library_fact_authorization_mismatch_or_replay", result.coverage.single().reason)
        assertEquals(0, receipts.snapshots)
        noncertifying(result)
    }

    @Test fun ordered_dependency_and_compiler_artifact_changes_abstain_before_authorization() = fixture { f ->
        val model = f.model()
        val original = f.selection(model)
        listOf(
            original.copy(request = original.request.copy(dependencyArtifacts = original.request.dependencyArtifacts.reversed())),
            original.copy(request = original.request.copy(compilerArtifacts = listOf(f.stdlib))),
            original.copy(applicability = original.applicability.copy(artifact = f.coroutines.copy(digest = Digests.sha256("changed")))),
        ).forEach { selection ->
            var authorizations = 0
            val result = RunBlockingInSuspendProvider(model, selection,
                LibraryFactAuthority { authorizations++; null }).analyze(f.input(model))
            assertEquals(0, authorizations)
            assertTrue(result.findings.isEmpty())
            noncertifying(result)
        }
    }

    @Test fun unsupported_version_adapter_and_native_selection_are_not_widened() = fixture { f ->
        val model = f.model()
        val original = f.selection(model)
        listOf(
            original.copy(applicability = original.applicability.copy(libraryVersion = "1.12.0")),
            original.copy(applicability = original.applicability.copy(adapterId = "kotlin-fir-next")),
            original.copy(applicability = original.applicability.copy(target = "iosSimulatorArm64")),
            original.copy(request = original.request.copy(compilerVersion = "2.4.10")),
        ).forEach { selection ->
            val result = RunBlockingInSuspendProvider(model, selection).analyze(f.input(model))
            assertTrue(result.findings.isEmpty())
            assertEquals("unavailable", result.coverage.single().state)
            noncertifying(result)
        }
    }

    @Test fun actual_blank_desktop_native_and_non_jvm_platform_contexts_never_retire_the_rule() = fixture { f ->
        listOf("" to "jvm", "desktop" to "jvm", "iosSimulatorArm64" to "native", "jvm" to "native").forEach { (target, platform) ->
            val model = f.model(target = target, platform = platform)
            var authorizations = 0
            val result = RunBlockingInSuspendProvider(model, f.selection(model),
                LibraryFactAuthority { authorizations++; null }).analyze(f.input(model))
            assertEquals(0, authorizations)
            assertTrue(result.findings.isEmpty())
            assertEquals("unavailable", result.coverage.single().state)
            assertTrue(result.coverage.single().required)
            noncertifying(result)
        }
    }

    @Test fun different_toolchain_identity_or_owner_added_effective_options_abstain_without_normalization() = fixture { f ->
        val model = f.model()
        val original = f.selection(model)
        listOf(
            original.copy(request = original.request.copy(toolchainId = Digests.sha256("other-toolchain"))),
            original.copy(request = original.request.copy(compilerArguments = original.request.compilerArguments + "-Xowner-added-effective-flag")),
        ).forEach { selection ->
            var authorizations = 0
            val result = RunBlockingInSuspendProvider(model, selection,
                LibraryFactAuthority { authorizations++; null }).analyze(f.input(model))
            assertEquals(0, authorizations)
            assertEquals("library_extraction_context_binding_mismatch", result.coverage.single().reason)
            assertTrue(result.findings.isEmpty())
            noncertifying(result)
        }
    }

    @Test fun extraction_source_root_inventory_and_refinement_are_exact_not_directory_inference() = fixture { f ->
        val model = f.model()
        val original = f.selection(model)
        listOf(
            original.copy(request = original.request.copy(sourceRoot = f.work.toString())),
            original.copy(request = original.request.copy(compilationId = "other")),
            original.copy(request = original.request.copy(sources = listOf(f.source.copy(sourceSet = "other")),
                sourceSets = listOf(SemanticSourceSet("other")), leafSourceSet = "other")),
        ).forEach { selection ->
            var authorizations = 0
            val result = RunBlockingInSuspendProvider(model, selection,
                LibraryFactAuthority { authorizations++; null }).analyze(f.input(model))
            assertEquals(0, authorizations)
            assertTrue(result.findings.isEmpty())
            noncertifying(result)
        }
    }

    @Test fun compiler_failure_unknown_types_and_foreign_shards_are_rejected_without_receipts() = fixture { f ->
        val model = f.model()
        val original = f.selection(model)
        val shard = original.shards.single()
        listOf(
            original.copy(compilerExitCode = 1),
            original.copy(compilerExitCode = null),
            original.copy(shards = listOf(shard.copy(unknown = listOf(UnknownSemanticFact("call", "opaque", f.span("runBlocking { 1 }")))))),
            original.copy(shards = listOf(shard.copy(snapshotId = Digests.sha256("foreign-snapshot")))),
            original.copy(shards = listOf(shard.copy(calls = shard.calls.map { it.copy(resultType = TypeFact("error", reason = "unknown")) }))),
        ).forEach { selection ->
            var authorizations = 0
            val result = RunBlockingInSuspendProvider(model, selection,
                LibraryFactAuthority { authorizations++; null }).analyze(f.input(model))
            assertEquals(0, authorizations)
            assertTrue(result.findings.isEmpty())
            noncertifying(result)
        }
    }

    @Test fun changed_snapshot_source_bytes_and_inconsistent_utf16_coordinates_discard_observations() = fixture { f ->
        val model = f.model()
        val original = f.selection(model)
        val input = f.input(model)
        val reviewed = RunBlockingInSuspendProvider(model, original).authorizationRequest(input)
        val changed = f.sourceBytes.copyOf().also { it[0] = 'X'.code.toByte() }
        val permit = f.syntheticPermit(model, reviewed, bytes = { changed })
        val result = RunBlockingInSuspendProvider(model, original, ExactLibraryFactAuthority(permit)).analyze(input)
        assertTrue(result.findings.isEmpty())
        assertEquals("library_snapshot_semantic_facts_unavailable", result.coverage.single().reason)
        val shard = original.shards.single()
        val bad = original.copy(shards = listOf(shard.copy(calls = shard.calls.map {
            it.copy(source = it.source.copy(startColumn = it.source.startColumn + 1))
        })))
        val other = f.analyze(model, bad)
        assertTrue(other.findings.isEmpty())
        noncertifying(other)
    }

    @Test fun utf16_locations_preserve_unicode_and_multiline_source_identity() =
        fixture("// é🙂\nsuspend fun owner() {\n    runBlocking { 1 }\n}\n") { f ->
            val model = f.model()
            val result = f.analyze(model, f.selection(model))
            val finding = result.findings.single()
            assertEquals(3, finding.location.line)
            assertEquals(4, finding.location.column)
            assertEquals(f.source.digest, finding.location.fileDigest)
            noncertifying(result)
        }

    @Test fun exposed_authorization_request_nested_collections_are_detached_and_unmodifiable() = fixture { f ->
        val model = f.model()
        val original = f.selection(model)
        val dependencies = original.request.dependencyArtifacts.toMutableList()
        val ids = original.applicability.selectedSymbolIds.toMutableList()
        val selected = original.copy(request = original.request.copy(dependencyArtifacts = dependencies),
            applicability = original.applicability.copy(selectedSymbolIds = ids))
        val request = RunBlockingInSuspendProvider(model, selected).authorizationRequest(f.input(model))
        dependencies.clear(); ids.clear()
        assertEquals(2, request.semanticRequest.dependencyArtifacts.size)
        assertEquals(1, request.applicability.selectedSymbolIds.size)
        assertFailsWith<UnsupportedOperationException> { (request.semanticRequest.dependencyArtifacts as MutableList<ArtifactIdentity>).clear() }
        assertFailsWith<UnsupportedOperationException> { (request.applicability.selectedSymbolIds as MutableList<String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (request.input.requiredRuleIds as MutableList<String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (request.input.snapshot.files as MutableList<SnapshotFile>).clear() }
        assertFailsWith<UnsupportedOperationException> { (request.input.context.classpath as MutableList<ArtifactIdentity>).clear() }
        assertFailsWith<UnsupportedOperationException> { (request.input.context.policies as MutableMap<String, String>).clear() }
    }

    @Test fun full_authorization_input_metadata_is_bounded_before_digest_or_owner_review() = fixture { f ->
        val model = f.model()
        val selection = f.selection(model)
        val input = f.input(model)
        var authorizations = 0
        val provider = RunBlockingInSuspendProvider(model, selection, LibraryFactAuthority { authorizations++; null })
        val oversized = input.copy(snapshot = input.snapshot.copy(captureKind = "x".repeat(1_500_000)))
        val result = provider.analyze(oversized)
        assertEquals(0, authorizations)
        assertEquals("library_fact_text_bound_exceeded", result.coverage.single().reason)
        assertTrue(result.findings.isEmpty())
        noncertifying(result)
        val metadata = provider.authorizationRequest(input)
        val hugeEnvironment = (0 until 256).associate { it.toString() to "x".repeat(6000) }
        val digestInput = metadata.copy(input = input.copy(execution = TrustedExecution("not-launched", environment = hugeEnvironment)))
        assertFailsWith<LibraryRuleProblem> { digestInput.digest() }
    }

    @Test fun changed_fact_content_after_owner_review_is_not_authorized_by_old_digest() = fixture { f ->
        val model = f.model()
        val original = f.selection(model)
        val input = f.input(model)
        val reviewed = RunBlockingInSuspendProvider(model, original).authorizationRequest(input)
        val receipts = LibraryRuleFixtures.Receipts()
        val permit = f.syntheticPermit(model, reviewed, receipts)
        val altered = original.copy(shards = original.shards.map { it.copy(calls = emptyList()) })
        val result = RunBlockingInSuspendProvider(model, altered, ExactLibraryFactAuthority(permit)).analyze(input)
        assertTrue(result.findings.isEmpty())
        assertEquals("library_extraction_not_authorized", result.coverage.single().reason)
        assertEquals(0, receipts.snapshots)
        noncertifying(result)
    }

    @Test fun authorizer_mutation_of_raw_fact_lists_cannot_change_admitted_frozen_content() = fixture { f ->
        val model = f.model()
        val original = f.selection(model)
        val mutableSymbols = original.shards.single().symbols.toMutableList()
        val selected = original.copy(shards = listOf(original.shards.single().copy(symbols = mutableSymbols)))
        val input = f.input(model)
        val reviewed = RunBlockingInSuspendProvider(model, selected).authorizationRequest(input)
        val permit = f.syntheticPermit(model, reviewed)
        val authority = LibraryFactAuthority {
            assertEquals(reviewed, it)
            mutableSymbols.clear()
            permit
        }
        val result = RunBlockingInSuspendProvider(model, selected, authority).analyze(input)
        assertEquals(1, result.findings.size)
        assertTrue(mutableSymbols.isEmpty())
        noncertifying(result)
    }

    @Test fun permit_replay_is_denied_without_repeating_receipts_or_readers() = fixture { f ->
        val model = f.model()
        val selection = f.selection(model)
        val input = f.input(model)
        val reviewed = RunBlockingInSuspendProvider(model, selection).authorizationRequest(input)
        val receipts = LibraryRuleFixtures.Receipts()
        val provider = RunBlockingInSuspendProvider(model, selection,
            ExactLibraryFactAuthority(f.syntheticPermit(model, reviewed, receipts)))
        assertEquals(1, provider.analyze(input).findings.size)
        val replay = provider.analyze(input)
        assertTrue(replay.findings.isEmpty())
        assertEquals("library_fact_authorization_mismatch_or_replay", replay.coverage.single().reason)
        assertEquals(1, receipts.extractions)
        assertEquals(1, receipts.reads)
        noncertifying(replay)
    }

    @Test fun extraction_receipt_error_is_unavailable_and_private_error_text_is_not_reported() = fixture { f ->
        val model = f.model()
        val selection = f.selection(model)
        val input = f.input(model)
        val reviewed = RunBlockingInSuspendProvider(model, selection).authorizationRequest(input)
        val receipts = LibraryRuleFixtures.Receipts()
        val permit = f.syntheticPermit(model, reviewed, receipts, afterExtraction = { error("private receipt detail") })
        val result = RunBlockingInSuspendProvider(model, selection, ExactLibraryFactAuthority(permit)).analyze(input)
        assertTrue(result.findings.isEmpty())
        assertEquals(0, receipts.artifacts)
        assertEquals(0, receipts.reads)
        assertFalse(ContractJson.encodeToString(result).contains("private receipt detail"))
        noncertifying(result)
    }

    @Test fun pre_cancelled_analysis_does_not_authorize_or_read() = fixture { f ->
        val model = f.model()
        var authorizations = 0
        val result = RunBlockingInSuspendProvider(model, f.selection(model),
            LibraryFactAuthority { authorizations++; null }).analyze(f.input(model), CancellationToken { true })
        assertEquals("cancelled", result.status)
        assertTrue(result.findings.isEmpty())
        assertEquals(0, authorizations)
    }

    @Test fun cancellation_at_snapshot_extraction_artifact_ownership_and_policy_receipts_stops_next_phase() = fixture { f ->
        val model = f.model()
        val selection = f.selection(model)
        val input = f.input(model)
        val reviewed = RunBlockingInSuspendProvider(model, selection).authorizationRequest(input)
        listOf("snapshot", "extraction", "artifact", "ownership", "policy").forEach { phase ->
            var cancelled = false
            val receipts = LibraryRuleFixtures.Receipts()
            val permit = f.syntheticPermit(model, reviewed, receipts,
                afterSnapshot = { if (phase == "snapshot") cancelled = true },
                afterExtraction = { if (phase == "extraction") cancelled = true },
                afterArtifact = { if (phase == "artifact") cancelled = true },
                afterOwnership = { if (phase == "ownership") cancelled = true },
                afterPolicy = { if (phase == "policy") cancelled = true })
            val result = RunBlockingInSuspendProvider(model, selection, ExactLibraryFactAuthority(permit))
                .analyze(input, CancellationToken { cancelled })
            assertEquals("cancelled", result.status)
            assertTrue(result.findings.isEmpty())
            assertEquals(0, receipts.reads)
            if (phase == "snapshot") assertEquals(0, receipts.extractions)
            if (phase == "extraction") assertEquals(0, receipts.artifacts)
            if (phase == "artifact") assertTrue(receipts.ownedSymbols.isEmpty())
            if (phase == "ownership") assertEquals(0, receipts.policies)
        }
    }

    @Test fun cancellation_after_a_staged_finding_discards_all_observations() =
        fixture("suspend fun owner() { runBlocking { 1 }; runBlocking { 2 } }\n") { f ->
            val model = f.model()
            val owner = f.owner()
            val target = f.target()
            val selection = f.selection(model, listOf(owner), listOf(target),
                listOf(f.call(target, owner), f.call(target, owner, "runBlocking { 2 }")))
            val input = f.input(model)
            val reviewed = RunBlockingInSuspendProvider(model, selection).authorizationRequest(input)
            val receipts = LibraryRuleFixtures.Receipts()
            var postReadChecks = 0
            // Post-reader checks: bytes return, validated assembly, shard, first call, second call.
            val token = CancellationToken { receipts.reads > 0 && ++postReadChecks >= 5 }
            val result = RunBlockingInSuspendProvider(model, selection,
                ExactLibraryFactAuthority(f.syntheticPermit(model, reviewed, receipts))).analyze(input, token)
            assertEquals("cancelled", result.status)
            assertTrue(result.findings.isEmpty())
            assertEquals(5, postReadChecks)
        }

    @Test fun cancellation_after_final_result_construction_discards_the_staged_observation() = fixture { f ->
        val model = f.model()
        val selection = f.selection(model)
        val input = f.input(model)
        val reviewed = RunBlockingInSuspendProvider(model, selection).authorizationRequest(input)
        val receipts = LibraryRuleFixtures.Receipts()
        var postReadChecks = 0
        // Bytes return, assembly, shard, call, before final construction, after final construction.
        val token = CancellationToken { receipts.reads > 0 && ++postReadChecks >= 6 }
        val result = RunBlockingInSuspendProvider(model, selection,
            ExactLibraryFactAuthority(f.syntheticPermit(model, reviewed, receipts))).analyze(input, token)
        assertEquals("cancelled", result.status)
        assertTrue(result.findings.isEmpty())
        assertEquals(6, postReadChecks)
    }

    @Test fun cancellation_from_source_reader_discards_all_observations() = fixture { f ->
        val model = f.model()
        val selection = f.selection(model)
        val input = f.input(model)
        val reviewed = RunBlockingInSuspendProvider(model, selection).authorizationRequest(input)
        val receipts = LibraryRuleFixtures.Receipts()
        var cancelled = false
        val permit = f.syntheticPermit(model, reviewed, receipts, bytes = { cancelled = true; f.sourceBytes.copyOf() })
        val result = RunBlockingInSuspendProvider(model, selection, ExactLibraryFactAuthority(permit))
            .analyze(input, CancellationToken { cancelled })
        assertEquals("cancelled", result.status)
        assertTrue(result.findings.isEmpty())
        assertEquals(1, receipts.reads)
    }

    @Test fun excessive_findings_discard_the_partial_staged_set_instead_of_truncating_coverage() = fixture { f ->
        val model = f.model()
        val owner = f.owner()
        val target = f.target()
        val selection = f.selection(model, listOf(owner), listOf(target), List(257) { f.call(target, owner) })
        val result = f.analyze(model, selection)
        assertTrue(result.findings.isEmpty())
        assertEquals("library_finding_bound_exceeded", result.coverage.single().reason)
        noncertifying(result)
    }

    @Test fun duplicate_empty_and_unimplemented_rule_selection_never_shrinks_obligations() = fixture { f ->
        val model = f.model()
        val selection = f.selection(model)
        val otherRules = listOf("ktc.coroutines.flowon-job-context", "ktc.serialization.unavailable-serializer",
            "ktc.ktor.owned-client-lifecycle-policy")
        val input = f.input(model, listOf(RUNBLOCKING_IN_SUSPEND) + otherRules)
        val result = f.analyze(model, selection, input)
        assertEquals(input.requiredRuleIds, result.coverage.map { it.ruleId })
        assertTrue(result.coverage.filter { it.ruleId in otherRules }.all { it.state == "unsupported" && it.required })
        noncertifying(result)
        val provider = RunBlockingInSuspendProvider(model, selection)
        val duplicate = provider.analyze(f.input(model, listOf(RUNBLOCKING_IN_SUSPEND, RUNBLOCKING_IN_SUSPEND)))
        assertTrue(duplicate.findings.isEmpty())
        assertEquals("library_rule_selection_duplicate", duplicate.coverage.single().reason)
        assertFalse(provider.analyze(f.input(model, emptyList())).complete())
        val unsupported = provider.analyze(f.input(model, otherRules))
        assertEquals(otherRules, unsupported.coverage.map { it.ruleId })
        assertTrue(unsupported.findings.isEmpty())
    }

    @Test fun excessive_text_and_recursive_type_shapes_fail_closed_before_authorization() = fixture { f ->
        val model = f.model()
        val original = f.selection(model)
        val cyclic = mutableListOf<TypeFact>()
        val type = TypeFact("intersection", components = cyclic)
        cyclic += type
        val shard = original.shards.single()
        listOf(
            original.copy(shards = listOf(shard.copy(unknown = listOf(UnknownSemanticFact("call", "x".repeat(1_500_000)))))),
            original.copy(shards = listOf(shard.copy(types = listOf(TypeUseFact(f.span("runBlocking { 1 }"), type))))),
        ).forEach { selected ->
            var authorizations = 0
            val result = RunBlockingInSuspendProvider(model, selected,
                LibraryFactAuthority { authorizations++; null }).analyze(f.input(model))
            assertEquals(0, authorizations)
            assertTrue(result.findings.isEmpty())
            noncertifying(result)
        }
    }

    @Test fun oversized_rule_envelope_cannot_expand_coverage_or_discharge_the_external_required_plan() = fixture { f ->
        val model = f.model()
        var authorizations = 0
        val provider = RunBlockingInSuspendProvider(model, f.selection(model), LibraryFactAuthority { authorizations++; null })
        val input = f.input(model, List(4097) { "required-$it" })
        val result = provider.analyze(input)
        assertEquals(0, authorizations)
        assertTrue(result.findings.isEmpty())
        assertEquals("library_analysis_envelope_bound_exceeded", result.reasons.single())
        assertFalse(result.completeFor(input))
        noncertifying(result)
    }
}

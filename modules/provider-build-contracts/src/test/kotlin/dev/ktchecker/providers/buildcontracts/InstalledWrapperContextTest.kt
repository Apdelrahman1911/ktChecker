package dev.ktchecker.providers.buildcontracts

import dev.ktchecker.contracts.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Owner capability/binding controls, not independently genuine selected-wrapper receipts. */
class InstalledWrapperContextTest {
    @Test fun default_inputs_need_every_mandatory_owner_attestation() {
        val selected = WrapperContractFixture().apply { selectedAllowed = false }
        assertUnavailable(selected.install(), WrapperUnavailableReason.SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE)
        val path = WrapperContractFixture().apply { pathAllowed = false }
        assertUnavailable(path.install(), WrapperUnavailableReason.VERIFICATION_PATH_ATTESTATION_UNAVAILABLE)
        val policy = WrapperContractFixture().apply { policyAllowed = false }
        assertUnavailable(policy.install(), WrapperUnavailableReason.POLICY_ATTESTATION_UNAVAILABLE)
    }

    @Test fun snapshot_receipt_is_checked_before_either_byte_reader() {
        val fixture = WrapperContractFixture().apply { snapshotAllowed = false }
        assertUnavailable(fixture.install(), WrapperUnavailableReason.SNAPSHOT_ATTESTATION_UNAVAILABLE)
        assertEquals(0, fixture.reads)
        assertEquals(0, fixture.selectedReceipts)
    }

    @Test fun snapshot_file_size_and_digest_are_independently_checked() {
        val fixture = WrapperContractFixture().apply { reader = { ByteArray(1) } }
        assertUnavailable(fixture.install(), WrapperUnavailableReason.SNAPSHOT_CONTENT_MISMATCH)
        val corrupted = WrapperContractFixture().apply { reader = { file ->
            if (file.path == WrapperContractFixture.PROPERTIES_PATH) sourceBytes.copyOf().also { it[0] = 0 }
            else error("Unexpected read")
        } }
        assertUnavailable(corrupted.install(), WrapperUnavailableReason.SNAPSHOT_CONTENT_MISMATCH)
    }

    @Test fun unknown_artifact_bytes_cannot_claim_the_known_profile() {
        val fixture = WrapperContractFixture()
        assertEquals(WrapperUnavailableReason.CONSUMER_PROFILE_UNAVAILABLE,
            assertFailsWith<WrapperContractProblem> { WrapperConsumerProfile.verify(ByteArray(47_505)) }.reason)
        assertEquals(0, fixture.reads)
    }

    @Test fun valid_profile_means_exact_bytes_not_class_names_in_caller_JSON() {
        val fixture = WrapperContractFixture()
        val admission = fixture.install()
        assertIs<WrapperContextAdmission.Installed>(admission)
        val bytes = fixture.lastReadCopies.getValue(WrapperContractFixture.JAR_PATH)
        WrapperConsumerProfile.verify(bytes)
        assertEquals(WrapperConsumerProfile.ARTIFACT_DIGEST, Digests.sha256(bytes))
    }

    @Test fun unknown_profile_with_hash_verified_fake_JAR_still_abstains() {
        val fixture = WrapperContractFixture()
        val fake = ByteArray(100)
        val files = fixture.input.snapshot.files.map { if (it.path == WrapperContractFixture.JAR_PATH)
            it.copy(digest = Digests.sha256(fake), size = fake.size.toLong()) else it }
        val input = fixture.input.copy(snapshot = fixture.input.snapshot.copy(files = files))
        val admission = InstalledWrapperContext.install(input, fixture.selection, WrapperRulePolicy(),
            verifySnapshot = { /* explicit synthetic registry for this malformed-artifact control */ },
            verifySelectedWrapper = { error("Must not reach selected owner evidence") },
            verifyVerificationPath = { error("Must not reach path evidence") },
            verifyRulePolicy = { error("Must not reach policy evidence") },
            readSnapshotBytes = { if (it.path == WrapperContractFixture.JAR_PATH) fake else fixture.sourceBytes })
        assertUnavailable(admission, WrapperUnavailableReason.CONSUMER_PROFILE_UNAVAILABLE)
    }

    @Test fun receipt_request_mismatches_do_not_authorize_selected_consumer_path_or_policy() {
        val other = Digests.sha256("other-request")
        assertUnavailable(WrapperContractFixture().apply { selectedRequestDigest = other }.install(),
            WrapperUnavailableReason.SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE)
        assertUnavailable(WrapperContractFixture().apply { pathRequestDigest = other }.install(),
            WrapperUnavailableReason.VERIFICATION_PATH_ATTESTATION_UNAVAILABLE)
        assertUnavailable(WrapperContractFixture().apply { policyRequestDigest = other }.install(),
            WrapperUnavailableReason.POLICY_ATTESTATION_UNAVAILABLE)
    }

    @Test fun unknown_or_unhashed_owner_evidence_is_not_receipt_authority() {
        assertUnavailable(WrapperContractFixture().apply { evidenceDigest = "not-a-digest" }.install(),
            WrapperUnavailableReason.SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE)
    }

    @Test fun installed_context_is_single_use_not_a_reusable_fresh_cache_claim() {
        val fixture = WrapperContractFixture()
        val provider = GradleWrapperChecksumProvider(fixture.install())
        assertEquals(1, provider.analyze(fixture.input).findings.size)
        val replay = provider.analyze(fixture.input)
        assertTrue(replay.findings.isEmpty())
        assertEquals(WrapperUnavailableReason.CONTEXT_REPLAY.code, replay.coverage.single().reason)
        assertNoncertifying(replay)
    }

    @Test fun cache_path_change_between_install_and_analysis_withdraws_old_fresh_assertion() {
        val fixture = WrapperContractFixture()
        val admission = fixture.install()
        fixture.verificationPath = WrapperVerificationPath.CACHED_WITHOUT_COMPARISON
        val result = GradleWrapperChecksumProvider(admission).analyze(fixture.input)
        assertEquals(WrapperUnavailableReason.VERIFICATION_PATH_ATTESTATION_CHANGED.code, result.coverage.single().reason)
        assertTrue(result.findings.isEmpty())
        assertNoncertifying(result)
    }

    @Test fun revoked_selected_or_policy_receipts_are_rechecked_before_observation() {
        val selected = WrapperContractFixture()
        val selectedAdmission = selected.install()
        selected.selectedAllowed = false
        assertEquals(WrapperUnavailableReason.SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE.code,
            GradleWrapperChecksumProvider(selectedAdmission).analyze(selected.input).coverage.single().reason)
        val policy = WrapperContractFixture()
        val policyAdmission = policy.install()
        policy.policyAllowed = false
        assertEquals(WrapperUnavailableReason.POLICY_ATTESTATION_UNAVAILABLE.code,
            GradleWrapperChecksumProvider(policyAdmission).analyze(policy.input).coverage.single().reason)
    }

    @Test fun reader_array_mutation_after_install_does_not_mutate_the_captured_properties() {
        val fixture = WrapperContractFixture()
        val admission = fixture.install()
        fixture.lastReadCopies.getValue(WrapperContractFixture.PROPERTIES_PATH).fill(0)
        fixture.lastReadCopies.getValue(WrapperContractFixture.JAR_PATH).fill(0)
        val result = GradleWrapperChecksumProvider(admission).analyze(fixture.input)
        assertEquals(1, result.findings.size)
        assertNoncertifying(result)
    }

    @Test fun original_policy_collection_mutation_does_not_mutate_frozen_suppression() {
        val fixture = WrapperContractFixture()
        val entries = mutableListOf(WrapperRuleSuppression(WRAPPER_CHECKSUM_FORMAT, WrapperContractFixture.PROPERTIES_PATH,
            fixture.scope, "independently-reviewed"))
        val admission = fixture.install(WrapperRulePolicy(suppressions = entries))
        entries.clear()
        val result = GradleWrapperChecksumProvider(admission).analyze(fixture.input)
        assertEquals("suppressed", result.coverage.single().state)
        assertTrue(result.findings.isEmpty())
    }

    @Test fun retained_input_collections_cannot_be_mutated_through_owner_request() {
        val fixture = WrapperContractFixture()
        val installed = assertIs<WrapperContextAdmission.Installed>(fixture.install()).context
        assertFailsWith<UnsupportedOperationException> { (installed.request.input.requiredRuleIds as MutableList<String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (installed.request.input.snapshot.files as MutableList<SnapshotFile>).clear() }
        assertFailsWith<UnsupportedOperationException> { (installed.request.input.context.policies as MutableMap<String, String>).clear() }
    }

    @Test fun snapshot_owner_worktree_scope_config_and_execution_binding_cannot_be_swapped() {
        val fixture = WrapperContractFixture()
        val changedInputs = listOf(
            fixture.input.copy(snapshot = fixture.input.snapshot.copy(ownerId = "different-owner")),
            fixture.input.copy(snapshot = fixture.input.snapshot.copy(worktreeId = "different-worktree")),
            fixture.input.copy(context = fixture.input.context.copy(configurationDigest = Digests.sha256("other-config"))),
            fixture.input.copy(context = fixture.input.context.copy(scope = fixture.scope.copy(module = ":other"))),
            fixture.input.copy(execution = TrustedExecution("/not-authorized-or-executed", approved = true)),
        )
        changedInputs.forEach { input ->
            val result = GradleWrapperChecksumProvider(fixture.install()).analyze(input)
            assertEquals(WrapperUnavailableReason.INPUT_BINDING_MISMATCH.code, result.coverage.single().reason)
            assertTrue(result.findings.isEmpty())
            assertNoncertifying(result)
        }
    }

    @Test fun absent_selected_files_duplicate_manifest_entries_and_unsafe_paths_abstain() {
        val fixture = WrapperContractFixture()
        assertUnavailable(fixture.install(actualSelection = fixture.selection.copy(propertiesPath = "missing.properties")),
            WrapperUnavailableReason.SNAPSHOT_FILE_SELECTION_UNAVAILABLE)
        val duplicate = fixture.input.copy(snapshot = fixture.input.snapshot.copy(files = fixture.input.snapshot.files +
            fixture.input.snapshot.files.first()))
        assertUnavailable(fixture.install(actualInput = duplicate), WrapperUnavailableReason.SNAPSHOT_FILE_SELECTION_UNAVAILABLE)
        listOf("../outside", "/absolute", "C:relative", "C:/absolute", "\\\\host\\share", "a\\b", "a//b").forEach { path ->
            assertUnavailable(fixture.install(actualSelection = fixture.selection.copy(propertiesPath = path)),
                WrapperUnavailableReason.SNAPSHOT_FILE_SELECTION_UNAVAILABLE)
        }
    }

    @Test fun unknown_model_capture_and_missing_toolchain_receipts_abstain_before_reads() {
        val fixture = WrapperContractFixture()
        val inputs = listOf(fixture.input.copy(context = fixture.input.context.copy(modelDigest = null)),
            fixture.input.copy(context = fixture.input.context.copy(toolchainDigest = null)),
            fixture.input.copy(context = fixture.input.context.copy(fullProject = false)),
            fixture.input.copy(snapshot = fixture.input.snapshot.copy(captureKind = "best_effort")),
            fixture.input.copy(snapshot = fixture.input.snapshot.copy(unknownInputs = listOf("unknown-wrapper-selection"))))
        inputs.forEach { input -> assertUnavailable(fixture.install(actualInput = input), WrapperUnavailableReason.VERIFIED_CONTEXT_UNAVAILABLE) }
        assertEquals(0, fixture.reads)
    }

    @Test fun oversize_declaration_descriptor_and_analysis_vector_are_not_trimmed_into_pass() {
        val fixture = WrapperContractFixture()
        val large = fixture.input.copy(snapshot = fixture.input.snapshot.copy(files = fixture.input.snapshot.files.map {
            if (it.path == WrapperContractFixture.PROPERTIES_PATH) it.copy(size = MAX_PROPERTIES_BYTES.toLong() + 1) else it }))
        assertUnavailable(fixture.install(actualInput = large), WrapperUnavailableReason.SNAPSHOT_FILE_SELECTION_UNAVAILABLE)
        val tooMany = fixture.input.copy(requiredRuleIds = List(4097) { WRAPPER_CHECKSUM_FORMAT })
        val result = GradleWrapperChecksumProvider().analyze(tooMany)
        assertTrue(result.coverage.isEmpty())
        assertEquals(listOf(WrapperUnavailableReason.ANALYSIS_ENVELOPE_BOUND_EXCEEDED.code), result.reasons)
        assertNoncertifying(result)
        assertEquals(0, fixture.reads)
    }

    @Test fun aggregate_identity_text_is_bounded_before_callbacks_and_serialization() {
        val fixture = WrapperContractFixture()
        val values = List(1024) { "x".repeat(4096) }
        val input = fixture.input.copy(context = fixture.input.context.copy(compilerArguments = values))
        assertUnavailable(fixture.install(actualInput = input), WrapperUnavailableReason.ANALYSIS_ENVELOPE_BOUND_EXCEEDED)
        assertEquals(0, fixture.snapshots)
        assertEquals(0, fixture.reads)
    }

    @Test fun cancellation_after_snapshot_attestation_stops_before_source_reading() {
        val fixture = WrapperContractFixture()
        var cancelled = false
        fixture.afterSnapshot = { cancelled = true }
        assertUnavailable(fixture.install(cancellation = CancellationToken { cancelled }), WrapperUnavailableReason.CANCELLED)
        assertEquals(0, fixture.reads)
    }

    @Test fun cancellation_after_selected_receipt_stops_before_path_or_policy_receipts() {
        val fixture = WrapperContractFixture()
        var cancelled = false
        fixture.afterSelected = { cancelled = true }
        assertUnavailable(fixture.install(cancellation = CancellationToken { cancelled }), WrapperUnavailableReason.CANCELLED)
        assertEquals(0, fixture.pathReceipts)
        assertEquals(0, fixture.policyReceipts)
    }

    @Test fun empty_justification_duplicate_policy_and_wrong_policy_ID_are_unavailable() {
        val fixture = WrapperContractFixture()
        val suppression = WrapperRuleSuppression(WRAPPER_CHECKSUM_FORMAT, WrapperContractFixture.PROPERTIES_PATH, fixture.scope, "reviewed")
        listOf(WrapperRulePolicy(suppressions = listOf(suppression.copy(justification = " "))),
            WrapperRulePolicy(suppressions = listOf(suppression, suppression)),
            WrapperRulePolicy(ruleId = "ktc.gradle.*")).forEach { policy ->
            assertUnavailable(fixture.install(policy), WrapperUnavailableReason.POLICY_UNSUPPORTED)
        }
        assertEquals(0, fixture.reads)
    }
}

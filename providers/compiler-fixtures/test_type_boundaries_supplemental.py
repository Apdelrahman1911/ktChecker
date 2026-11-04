#!/usr/bin/env python3
"""Static supplemental scheduling/source boundaries only; no Kotlin/compiler/provider execution."""
from collections import Counter
from pathlib import Path
import hashlib
import json
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / 'providers/compiler-fixtures'
KOTLIN = ROOT / 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler'
REVIEWED_IDS = [
    ('ktc.compiler.common.int_literal_out_of_range', 'INT_LITERAL_OUT_OF_RANGE'),
    ('ktc.compiler.common.cannot_check_for_erased', 'CANNOT_CHECK_FOR_ERASED'),
    ('ktc.compiler.common.recursive_typealias_expansion', 'RECURSIVE_TYPEALIAS_EXPANSION'),
    ('ktc.compiler.common.conflicting_upper_bounds', 'CONFLICTING_UPPER_BOUNDS'),
    ('ktc.compiler.common.type_variance_conflict_error', 'TYPE_VARIANCE_CONFLICT_ERROR'),
    ('ktc.compiler.common.unresolved_reference_wrong_receiver', 'UNRESOLVED_REFERENCE_WRONG_RECEIVER'),
]
SOURCE_ONLY_PREIMAGE_HASHES = {
    'manifest.json': '4854aa356caba6e90b48e10e333bf32828460dc64b04dd0d6b86db3653d49774',
    'planned-kmp-manifest.json': '804c242c62707457ff143d16fb03bcd1bddd2ca84b3f9aef2085dc5af800b128',
    'generate_fixtures.py': '73cfab45c982081da4088daa6fc2b266f16d24c828010772966ea201d1372b69',
    'cohort_expansion.py': '2dd0d0323b507da9f4ff3e758ab0951f53fcdf196a46006331e5e33681f33edb',
    'authored-input-inventory.json': 'b8c6af0b0f1d68578ad9a209e900c83668fc25f67bfd7fbf272fc28720511bad',
}
# Reviewed A1 + v4 + C source segments, not process/timing/qualification receipts.
UNCHANGED_RUNNER_SEGMENTS = {
    'execute': ('    private fun pointMatches(', '987acbe69154a72f96cce02197eb9bc853c12bc1643abab7e34076267fe473dc'),
    'runFixtures': ('    private fun cohort(', '3b8a9cd031f32a0ad79f8fcb9d7a2ce02f68fede8d15f65fad6b36a69b250edd'),
    'cohort': ('    private fun execute(', 'bcf1e0a3e41a5d7d3aacb48a45d7dc7a9642ce6f797408c2d2b50c1a6b6e0481'),
    'probeFixture': ('    private fun runTimingProbeSamples(', '182142918a18cdb94d145994f89bb78c91da69ced12a66882327eb5ce2c637d8'),
    'runTimingProbeSamples': ('    private fun scheduledWorkComplete(', '22ffaf7a1a0a845cf2f0f20fd36a4d21a5967b4211b18cb5bd7c69b5fb5a3c9f'),
    'scheduledWorkComplete': ('    private fun probeScope(', 'e68de0f38d79dcff44efe95fff1f55fbfeefb890f6518a65b3f170a64cc5a247'),
    'probeScope': ('    private fun probeCheckpoint(', 'd33bc46695936eb5d311d1a6205ae3eb0de98cc90806b8264327a53ff3041b06'),
    'probeCheckpoint': ('    private fun runFixtures(', '0d5e7e8979af4d77450474c5ff82db8c24a1d6fe2a459c6a662cc40c2fda3fb5'),
    'runIntentControls': ('    private fun runTransportControls(', '77809e6e043827f726dd0e63a639659dc123609e9a25f01af2963e34265c0ccd'),
    'runTransportControls': ('    private fun recordControl(', 'f11cb5e195594c16856f142956647a311cb9759f505e3ddc76965683df92bafc'),
    'recordControl': ('    private data class Checkpoint(', '86b782010d057499a01004951d5258c40de3b48522a8ab32a02afe80ceaebf75'),
}


def segment(source, name, next_marker):
    start = source.index('    private fun ' + name + '(')
    return source[start:source.index(next_marker, start)]


class TypeBoundarySupplementalSourceContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads((FIXTURES / 'manifest.json').read_text(encoding='utf-8'))
        cls.rows = [row for row in cls.manifest['fixtures'] if row.get('authored_cohort') == '2026-11-04-type_boundaries']
        cls.runner = (KOTLIN / 'CompilerFixtureRunner.kt').read_text(encoding='utf-8')
        cls.controls = (KOTLIN / 'CompilerFixtureControls.kt').read_text(encoding='utf-8')
        cls.helper = (KOTLIN / 'CompilerFixtureSupplemental.kt').read_text(encoding='utf-8')
        cls.kotlin_tests = (KOTLIN / 'CompilerFixtureSupplementalTest.kt').read_text(encoding='utf-8')

    def test_all_existing_fixture_and_initial_authoring_bytes_are_preserved(self):
        for relative, expected in SOURCE_ONLY_PREIMAGE_HASHES.items():
            self.assertEqual(expected, hashlib.sha256((FIXTURES / relative).read_bytes()).hexdigest(), relative)
        files = sorted([*(FIXTURES / 'cases').rglob('*.kt'), *(FIXTURES / 'planned-kmp-cases').rglob('*.kt')],
                       key=lambda path: path.relative_to(FIXTURES).as_posix())
        self.assertEqual(278, len(files))
        inventory = ''.join(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' +
                            path.relative_to(FIXTURES).as_posix() + '\n' for path in files)
        self.assertEqual('378127f94364be6ac94bf84d56769ea25126f28840686f03fa5adafc55c7bc00',
                         hashlib.sha256(inventory.encode('utf-8')).hexdigest())
        variants = [variant for row in self.rows for variant in row['supplemental_inputs']]
        self.assertEqual(18, len(variants))
        for variant in variants:
            self.assertEqual('unrun_supplemental_source_plan', variant['status'])
            self.assertEqual('not_run', variant['execution_state'])
            self.assertIs(False, variant['executed_by_current_pair_harness'])
            self.assertEqual(['-jvm-target', '17'], variant['compiler_flags'])
            path = FIXTURES / variant['path']
            self.assertFalse(path.is_symlink())
            self.assertEqual(path, path.resolve())
            self.assertEqual(variant['source_sha256'], hashlib.sha256(path.read_bytes()).hexdigest())

    def test_literal_six_id_eighteen_path_digest_plan_is_exact_and_kind_major(self):
        ids = re.findall(r'Rule\("(ktc\.compiler\.common\.[a-z_]+)", "([A-Z_]+)"', self.helper)
        self.assertEqual(REVIEWED_IDS, ids)
        literals = re.findall(r'"(boundary|applicability|suppression)" to \("(cases/[a-z_]+/[a-z]+\.kt)" to "([0-9a-f]{64})"\)', self.helper)
        expected = [(variant['kind'], variant['path'], variant['source_sha256'])
                    for row in self.rows for variant in row['supplemental_inputs']]
        self.assertEqual(expected, literals)
        self.assertEqual(18, len({path for _, path, _ in literals}))
        self.assertIn('val KINDS = listOf("boundary", "applicability", "suppression")', self.helper)
        self.assertIn('= KINDS.flatMap { kind -> RULES.map { rule ->', self.helper)
        self.assertIn('declaration.copy(number = index + 1)', self.helper)
        for constant in ('INPUT_COUNT = 18', 'PLANNED_ATTEMPTS = 23', 'CHILD_CAPABLE_CALLS = 22'):
            self.assertIn(constant, self.helper)
        self.assertIn('val FLAGS = listOf("-jvm-target", "17")', self.helper)
        self.assertIn('"source_transforms" to 0', self.helper)
        self.assertIn('"cohort_batches" to 0', self.helper)
        self.assertIn('"retries_or_replacements" to 0', self.helper)
        self.assertIn('"ordinary_six_kind_jobs" to 0', self.helper)
        self.assertIn('"version_denial_attempt" to 19', self.helper)
        self.assertIn('index + 20', self.helper)

    def test_actual_scheduling_uses_one_existing_captured_file_per_input_and_no_transforms_or_reruns(self):
        run = segment(self.runner, 'runSupplementalInputs', '    private fun probeFixture(')
        self.assertEqual(2, len(re.findall(r'\bexecute\(', run)))
        self.assertIn('supplementalInputs.forEach { input ->', run)
        self.assertIn('execute(declaration.label, mapOf(declaration.path to input.source), CompilerFixtureSupplementalPlan.FLAGS)', run)
        self.assertIn('execute(CompilerFixtureSupplementalPlan.VERSION_DENIAL_LABEL', run)
        self.assertIn('requestedVersion = "2.4.20"', run)
        self.assertIn('mismatch.process == null', run)
        self.assertIn('mismatch.input.context.compilerVersion == "2.4.20"', run)
        for forbidden in ('suppress(', 'changePackage(', 'ensureEmit(', 'cohort(', '.positive', '.negative', 'while ('):
            self.assertNotIn(forbidden, run)
        self.assertIn('CompilerFixtureSupplementalPlan.load(manifest, provenance::readFixture)', self.runner)
        self.assertIn('fixtureRuleIdsByPath.put(declaration.path, declaration.ruleId) == null', self.runner)
        self.assertIn('Digests.sha256(source).removePrefix("sha256:") == declaration.sourceSha256', self.helper)
        self.assertIn('initial_manifest_authoring_record', run)
        self.assertNotIn('assess(', run)

    def test_a1_execution_ordinary_and_c_probe_control_source_segments_are_identical(self):
        for name, (next_marker, expected) in UNCHANGED_RUNNER_SEGMENTS.items():
            actual = segment(self.runner, name, next_marker).encode('utf-8')
            self.assertEqual(expected, hashlib.sha256(actual).hexdigest(), name)
        self.assertEqual('2f89dd7fe83eb2189c310d7a6f972b2f2a9f409e3c6d3c4cb7963d7077ef43cf',
                         hashlib.sha256((KOTLIN / 'CompilerFixtureTimingProbe.kt').read_bytes()).hexdigest())
        self.assertEqual('cdc3c99660e318e06d4a5f435057f6353d35a3cad5346dbd1780ffb69ea0eaa4',
                         hashlib.sha256((KOTLIN / 'CompilerFixtureTimingProbeTest.kt').read_bytes()).hexdigest())

    def test_supplemental_selection_is_separate_and_all_eleven_original_id_adapters_are_preserved(self):
        calls = re.findall(r'\bsettings\.selects\(([^)]*)\)', self.runner)
        self.assertEqual(Counter({'it.authoredCohort, it.ruleId': 4, 'fixture.authoredCohort, fixture.ruleId': 7}), Counter(calls))
        selection = self.controls[self.controls.index('    fun selects('):self.controls.index('    fun selectsSupplemental(')]
        self.assertIn('if (cohort == CompilerFixtureSupplementalPlan.SELECTOR) return false', selection)
        self.assertIn('ORIGINAL_ID_COHORTS[cohort] ?: return cohort == "all" || cohort == name', selection)
        supplemental = self.controls[self.controls.index('    fun selectsSupplemental('):self.controls.index('    fun description(')]
        self.assertIn('cohort != CompilerFixtureSupplementalPlan.SELECTOR || name != "type_boundaries"', supplemental)
        self.assertIn('require(ruleId in CompilerFixtureSupplementalPlan.RULE_IDS)', supplemental)
        self.assertIn('require(kind in CompilerFixtureSupplementalPlan.KINDS)', supplemental)
        self.assertIn('val COHORTS = AUTHORED_COHORT_SELECTORS + ORIGINAL_ID_COHORTS.keys + CompilerFixtureSupplementalPlan.SELECTOR', self.controls)
        self.assertIn('if (supplementalSelected()) runSupplementalInputs()', self.runner)
        for bound in ('MAX_ATTEMPTS = 48', 'MAX_OVERALL_MILLIS = 1_200_000L', 'MAX_PER_JOB_MILLIS = 90_000L',
                      'MAX_EVIDENCE_BYTES = 268_435_456L', 'JOB_RESERVE_BYTES = 16_777_216L', 'TERMINAL_RESERVE_BYTES = 8_388_608L'):
            self.assertIn(bound, self.controls)

    def test_completion_is_conservative_private_and_not_laundered_from_failure_or_unknown_expectations(self):
        self.assertIn('if (declaration.kind == "suppression") null else', self.helper)
        self.assertIn('actualExitCode != 0 -> "failed_compilation"', self.helper)
        self.assertIn('providerStatus == "failed" -> "failed_provider_result_despite_exit0"', self.helper)
        self.assertIn('unvalidated_compiler_accepted_suppression_with_ERROR_SUPPRESSION_warnings', self.helper)
        self.assertIn('UNSPECIFIED and WILL NOT BE PRESERVED', self.helper)
        self.assertIn('observations.all { it.expectationMatched == true }', self.helper)
        self.assertIn('"run_complete" to false, "ordinary_six_kind_assessments_complete" to false', self.helper)
        self.assertIn('"supplemental_observed_receipts_are_success" to false', self.helper)
        self.assertNotIn('"supplemental_complete" to true', self.helper + self.runner)
        self.assertIn('if (selected) null else ordinaryOrProbeSidecar', self.helper)
        self.assertIn('CompilerFixtureEvidence(repository, runRoot, latestSidecar(), settings.evidenceBytes)', self.runner)
        self.assertIn('latestSidecar()?.let { save(it, sidecar,', self.runner)
        gate = segment(self.runner, 'boundedWorkComplete', '    private fun supplementalScope(')
        self.assertIn('CompilerFixtureSupplementalPlan.expectedVariantsValidated(', gate)
        self.assertIn('supplementalVersionDenial == true && controls.values.all { it == true }', gate)
        self.assertIn('else scheduledWorkComplete()', gate)
        self.assertIn('Supplemental observations remain partial: failed or unknown expectations are not validated', self.runner)
        self.assertIn('supplemental_observed_receipts_complete', self.runner)
        self.assertIn('provenanceIntact != false', self.runner)

    def test_all_98_ordinary_16_planned_443_mappings_remain_visible_without_six_kind_promotion(self):
        self.assertEqual(98, len(self.manifest['fixtures']))
        self.assertEqual(16, len(json.loads((FIXTURES / 'planned-kmp-manifest.json').read_text())['fixtures']))
        self.assertEqual(443, len(json.loads((ROOT / 'modules/provider-compiler/src/main/resources/compiler-rule-map.json').read_text())['mappings']))
        self.assertIn('allFixtures.map { fixture ->', self.runner)
        self.assertIn('"planned_unsupported_common_jvm_graphs" to 16', self.runner)
        self.assertIn('"catalog_mappings" to 443', self.runner)
        self.assertIn('not_run_ordinary_six_kind_outside_supplemental_schedule', self.runner)
        self.assertIn('private val CASE_KINDS = listOf("positive", "negative", "configuration", "version", "suppression", "regression")', self.runner)
        self.assertIn('val implementationObserved = cases.any { it.kind == "positive" && it.passed }', self.runner)
        self.assertIn('"tested" to (implemented && allCases)', self.runner)
        self.assertIn('"qualified" to false', self.runner)
        self.assertIn('"supplemental_input_inventory" to supplementalInventory(terminal, mapping.ruleId)', self.runner)
        self.assertIn('"historical_tuple_policy"', self.runner)

    def test_twelve_kotlin_value_tests_are_authored_without_any_provider_or_process_invocation(self):
        self.assertEqual(12, len(re.findall(r'@Test fun ', self.kotlin_tests)))
        for test in ('everyNonzeroSuppressionIncludingInternalCompilerErrorValuesStaysFailedNeverPassing',
                     'exit0SuppressionWithUnspecifiedWarningsOrNoWarningsRemainsUnknownAndUnvalidated',
                     'receiptCompletenessRequiresAllExactOrdinalsSubjectsAndDeclarationsNotJustEighteenCounts',
                     'privateDestinationAndCompletionOverlayCannotWriteOrCompleteTheOrdinaryLatestTuple'):
            self.assertIn(test, self.kotlin_tests)
        for forbidden in ('ProcessOutcome(', 'ProcessBuilder(', 'KotlinJvmCompilerProvider(', '.analyze(', '.analyzeObserved(', 'Runtime.getRuntime('):
            self.assertNotIn(forbidden, self.kotlin_tests + self.helper)
        self.assertIn('No compiler, provider or native helper is launched or simulated', self.kotlin_tests)
        self.assertIn('Value gate only; no actual receipts or process claimed', self.kotlin_tests)


if __name__ == '__main__':
    unittest.main()

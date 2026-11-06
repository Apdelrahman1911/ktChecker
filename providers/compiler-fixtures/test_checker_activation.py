#!/usr/bin/env python3
"""Static source/data controls only; never import a candidate or invoke compiler/provider tools."""
from collections import Counter
from pathlib import Path
import ast
import hashlib
import json
import re
import unittest

import compiler_mapping_contracts as contracts

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / 'providers/compiler-fixtures'
MAP_REL = 'modules/provider-compiler/src/main/resources/compiler-rule-map.json'
FLAGS_REL = 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerFlags.kt'
PROVIDER_REL = 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmCompilerProvider.kt'
KOTLIN_TEST_REL = 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerCheckerActivationTest.kt'
PLAN_REL = 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureDiagnosticBreadth.kt'
EVIDENCE_SHA256 = '69d376edc340cefac3deef190e17d329f20968885ee990bc36134a8e6e9731ec'
FLAGS_SHA256 = '750ca4544a2b801bb6020838410ec92e7278a1a6a2ca4f10e58cd17b10819271'
GENERATOR_SHA256 = '8b99ee41d6f9942ad742ea24e8365e17a862b7f5b9cb1167f1d50a2867bc617d'
KOTLIN_TEST_SHA256 = '84612259c31f03bbb8e85bd9606e7b0f3f5a1874fb77ec4a1c3629c7bfb29039'
COMMON_POLICIES = {
    'ARRAY_EQUALITY_OPERATOR_CAN_BE_REPLACED_WITH_CONTENT_EQUALS': 'advisory',
    'REDUNDANT_CALL_OF_CONVERSION_METHOD': 'opt_in',
    'UNUSED_EXPRESSION': 'advisory',
    'UNUSED_LAMBDA_EXPRESSION': 'advisory',
}
EXTRA_IDS = {
    'ASSIGNED_VALUE_IS_NEVER_READ', 'CAN_BE_VAL', 'CAN_BE_VAL_DELAYED_INITIALIZATION', 'CAN_BE_VAL_LATEINIT',
    'REDUNDANT_MODALITY_MODIFIER', 'REDUNDANT_RETURN_UNIT_TYPE', 'REDUNDANT_SETTER_PARAMETER_TYPE',
    'REDUNDANT_SINGLE_EXPRESSION_STRING_TEMPLATE', 'REDUNDANT_VISIBILITY_MODIFIER', 'UNREACHABLE_CODE',
    'UNUSED_ANONYMOUS_PARAMETER', 'UNUSED_VARIABLE', 'USELESS_CALL_ON_NOT_NULL',
    'VARIABLE_INITIALIZER_IS_REDUNDANT', 'VARIABLE_NEVER_READ',
}
BREADTH_TEST_NAMES = ['test_all_seven_whole_file_successor_pins_and_four_data_doc_pins_bind_the_reviewed_dependency_graph',
 'test_all_325_old_source_pins_keep_278_ordinary_graph_sources_30_nullability_and_existing_metadata',
 'test_exact_compatibility_reconstruction_preserves_historical_identities_and_rejects_unrelated_mutations',
 'test_literal_fifteen_rules_ninety_cases_flags_regions_and_companions_are_bound_without_manifest_scheduling',
 'test_six_exact_metadata_pins_capture_all_ninety_sources_plus_data_docs_and_both_python_controls',
 'test_only_three_closed_selectors_add_separate_35_40_40_bounds_without_altering_any_old_cap_or_selects_call',
 'test_conditional_provenance_keeps_old_signatures_scopes_and_checks_the_same_96_paths_at_start_and_terminal',
 'test_loader_validates_all_headers_records_and_oracles_before_source_reads_then_reuses_unchanged_positives',
 'test_runner_registers_ninety_unique_paths_once_binds_existing_mapping_and_never_appends_ordinary_assessments',
 'test_fixed_single_source_calls_denial_and_four_unchanged_controls_have_no_transform_retry_or_custody_fallback',
 'test_actual_full_raw_and_mapped_inventories_custody_fields_and_execute_failures_are_retained_without_residual_only_oracles',
 'test_two_member_snapshot_gate_matches_unchanged_executor_metadata_without_filtering_extra_members',
 'test_selected_pair_regions_exact_counts_severities_multisets_and_raw_unmapped_companion_cannot_hide_errors',
 'test_exact_schedule_receipt_and_denial_value_gates_never_replace_actual_permit_or_process_evidence',
 'test_unknown_error_suppression_and_actual_failed_tasks_remain_unvalidated_without_ordinary_global_or_latest_promotion',
 'test_invocation_scope_checkpoints_rule_inventory_and_terminal_keep_all_selected_unselected_and_failed_observations_visible',
 'test_nineteen_existing_engine_sections_seven_nullability_sections_and_entire_budget_suffix_are_byte_identical',
 'test_thirty_five_kotlin_and_source_data_controls_do_not_create_processes_authority_or_execution_receipts']


def digest(data):
    return hashlib.sha256(data).hexdigest()


def literal_constant(path, name):
    tree = ast.parse(path.read_text(encoding='utf-8'))
    values = [node.value for node in tree.body if isinstance(node, ast.Assign)
              and any(isinstance(target, ast.Name) and target.id == name for target in node.targets)]
    if len(values) != 1:
        raise ValueError('Expected one source literal: ' + name)
    return ast.literal_eval(values[0])


class CompilerCheckerActivationSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Affirmative445 production contract, distinct from every frozen legacy443 oracle below.
        cls.current_mapping = contracts.current445(ROOT)
        contracts.require_legacy_runner_refusal_source(ROOT)
        cls.map_bytes = (ROOT / MAP_REL).read_bytes()
        cls.mapping = cls.current_mapping
        cls.rows = cls.mapping['mappings']
        cls.by_id = {row['upstream_id']: row for row in cls.rows}
        cls.evidence_bytes = (FIXTURES / 'checker-activation-2.4.21.json').read_bytes()
        cls.evidence = json.loads(cls.evidence_bytes)
        cls.flags = (ROOT / FLAGS_REL).read_text(encoding='utf-8')

    def test_authoritative_tagged_source_proves_registration_not_runtime_or_policy_enablement(self):
        self.assertEqual(EVIDENCE_SHA256, digest(self.evidence_bytes))
        self.assertEqual('2.4.21', self.evidence['kotlin_version'])
        self.assertEqual('K2 JVM source-session registration', self.evidence['backend_route'])
        self.assertFalse(self.evidence['compiler_factory_section_is_registration_evidence'])
        self.assertFalse(self.evidence['owner_policy_or_default_changes'])
        self.assertEqual(0, self.evidence['observed_compiler_provider_runs'])
        self.assertEqual({'implemented': 0, 'tested': 0, 'qualified': 0}, self.evidence['stage_credit'])
        self.assertEqual('6ee2c2026ab8fdde96e6f14b84262dfa3ac22ea7510e82b42489d846735b9202',
                         self.evidence['primary_archive']['sha256'])
        refs = {row['id']: row for row in self.evidence['source_references']}
        self.assertEqual(84, len(refs))
        for row in refs.values():
            self.assertEqual('v2.4.21', row['tag'])
            self.assertEqual('tagged_primary_source_not_observed_execution', row['evidence_kind'])
            self.assertEqual(row['quote_sha256'], digest(row['quote'].encode()))
            self.assertEqual(row['line_end'] - row['line_start'] + 1, len(row['quote'].splitlines()))
            self.assertTrue(row['url'].startswith('https://github.com/JetBrains/kotlin/blob/v2.4.21/' + row['path'] + '#L'))
        self.assertIn('registerCommonCheckers()', refs['source_common_unconditional']['quote'])
        self.assertIn('if (extraAnalysisMode)', refs['source_extra_optional']['quote'])
        self.assertIn('override fun isEnabled(): Boolean = true', refs['unused_enabled']['quote'])
        self.assertEqual('4a2812b365c43922954772ffacbac9a0eb7b15d34b4665d4badb6a8ea18882d2',
                         refs['source_common_unconditional']['sha256'])
        for id_ in COMMON_POLICIES:
            registration = refs[id_.lower() + '_registration']
            is_expression = id_.startswith(('ARRAY_EQUALITY_', 'REDUNDANT_CALL_'))
            self.assertTrue(registration['path'].endswith('CommonExpressionCheckers.kt' if is_expression else 'CommonDeclarationCheckers.kt'))
            self.assertEqual('82ea468f1614d83c91dffac3211067fe0409091e18a567ef6f7a0c5a8dbdadd0' if is_expression else
                             'c74daf404ef6e02aec8a8a29d50bd7ec06a35523ae0d2049b0e4d979726e6039', registration['sha256'])

    def test_four_common_flags_are_corrected_but_all_real_extra_and_experimental_prerequisites_remain(self):
        self.assertEqual((1, 'kotlin-compiler-jvm', '2.4.21'),
            (self.mapping['schema_version'], self.mapping['provider_id'], self.mapping['provider_version']))
        self.assertEqual(sorted(COMMON_POLICIES), self.evidence['common_extra_section_checker_ids'])
        self.assertEqual(sorted(EXTRA_IDS), self.evidence['extra_checker_ids'])
        self.assertEqual(['EMPTY_RANGE'], self.evidence['experimental_checker_ids'])
        expected_flags = {id_: ['-Wextra'] for id_ in EXTRA_IDS}
        expected_flags['EMPTY_RANGE'] = ['-Xuse-fir-experimental-checkers']
        self.assertEqual(expected_flags, {row['upstream_id']: row['known_checker_activation_flags']
                                         for row in self.rows if row['known_checker_activation_flags']})
        for id_, policy in COMMON_POLICIES.items():
            row = self.by_id[id_]
            self.assertEqual(('common', 'WARNING', 'Extra checkers', policy, []),
                (row['backend'], row['declared_severity'], row['source_section'], row['default_activation'], row['known_checker_activation_flags']))
            self.assertEqual(['Exact mapping availability alone does not establish per-ID fixture testing or tuple qualification'], row['gaps'])
        groups = Counter(row['registration_group'] for row in self.evidence['checker_registrations'])
        self.assertEqual({'common': 4, 'extra': 15, 'experimental': 1}, dict(groups))

    def test_all443_catalog_links_policy_kinds_severities_and_backend_counts_are_preserved(self):
        catalog = {row['id']: row for row in json.loads((ROOT / 'data/rules-core.json').read_bytes())
                   if row['id'].startswith('ktc.compiler.')}
        # The method's historical443 name is retained; production is affirmatively445.
        self.assertEqual(445, len(catalog))
        self.assertEqual(445, len(self.rows))
        self.assertEqual(445, len(self.by_id))
        self.assertEqual(contracts.CURRENT_CONTRACT, self.mapping['mapping_contract'])
        self.assertEqual(set(catalog), {row['rule_id'] for row in self.rows})
        self.assertEqual({'common': 294, 'jvm': 99, 'native': 43, 'ir': 9}, dict(Counter(row['backend'] for row in self.rows)))
        self.assertEqual({'enabled_when_validated': 330, 'advisory': 63, 'opt_in': 52},
                         dict(Counter(row['default_activation'] for row in self.rows)))
        for row in self.rows:
            original = catalog[row['rule_id']]
            self.assertEqual(digest(json.dumps(original, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()), row['catalog_record_sha256'])
            for field in ('upstream_id', 'kind', 'certainty', 'default_activation'):
                self.assertEqual(original[field], row[field], row['rule_id'] + ':' + field)
            self.assertEqual('exact', row['relation'])
            self.assertEqual(original['category'].removeprefix('kotlin.compiler.'), row['backend'])
            self.assertEqual(original['compiler_factory']['source_section'], row['source_section'])
            self.assertEqual(original['compiler_factory']['declaration_severity'], row['declared_severity'])
            self.assertEqual(original['sources'][0], row['source'])

    def test_generator_and_runtime_have_the_same_four_exact_version_and_backend_bound_exceptions(self):
        generator = FIXTURES / 'generate_rule_map.py'
        self.assertEqual(GENERATOR_SHA256, digest(generator.read_bytes()))
        self.assertEqual(FLAGS_SHA256, digest((ROOT / FLAGS_REL).read_bytes()))
        self.assertEqual('2.4.21', literal_constant(generator, 'VERSION'))
        self.assertEqual(tuple(COMMON_POLICIES), literal_constant(generator, 'COMMON_EXTRA_SECTION_CHECKERS_KOTLIN_2421'))
        match = re.search(r'private val commonExtraSectionCheckersKotlin2421 = setOf\((.*?)\n    \)', self.flags, re.S)
        self.assertIsNotNone(match)
        self.assertEqual(list(COMMON_POLICIES), re.findall(r'"([A-Z_]+)"', match.group(1)))
        self.assertIn('CompilerRuleRegistry.VERSION == "2.4.21" && mapping.backend == "common"', self.flags)
        self.assertIn('mapping.upstreamId in commonExtraSectionCheckersKotlin2421', self.flags)
        self.assertIn('!commonCheckerInPinnedVersion', self.flags)
        text = generator.read_text(encoding='utf-8')
        self.assertIn("VERSION == '2.4.21' and row['category'] == 'kotlin.compiler.common'", text)
        self.assertIn('not common_checker_in_pinned_version', text)
        self.assertIn("'provider_version': VERSION", text)

    def test_all443_current_implementation_pins_and_only_four_semantic_deltas_invert_to_previous_map(self):
        main = sorted((ROOT / 'modules/provider-compiler/src/main/kotlin').rglob('*.kt'))
        self.assertEqual(23, len(main))
        self.assertEqual(list(contracts.CURRENT_IMPLEMENTATION_SOURCE_PINS), [p.relative_to(ROOT).as_posix() for p in main])
        expected = [{'path': p.relative_to(ROOT).as_posix(), 'sha256': digest(p.read_bytes())} for p in main]
        self.assertEqual(contracts.current_implementation_references(ROOT), expected)
        self.assertEqual(self.map_bytes, (json.dumps(self.mapping, indent=2) + '\n').encode())
        for row in self.rows:
            self.assertEqual(expected, row['implementation_evidence'])
        # This narrow four-flag inverse remains a frozen443 DATA regression, not a current445 loader.
        before = contracts.legacy_mapping(ROOT)
        self.assertEqual(443, len(before['mappings']))
        historical_evidence = [{'path': ref['path'], 'sha256': digest(contracts.historical_source_bytes(ROOT, ref['path']))}
                               for ref in before['mappings'][0]['implementation_evidence']]
        for row in before['mappings']:
            self.assertEqual(historical_evidence, row['implementation_evidence'])
            flags_refs = [ref for ref in row['implementation_evidence'] if ref['path'] == FLAGS_REL]
            self.assertEqual([{'path': FLAGS_REL, 'sha256': FLAGS_SHA256}], flags_refs)
            flags_refs[0]['sha256'] = 'fb19a9de4bab6c1f2273b1d7466ce1fb4dd18fa9ca38e5e09139f12612af3b37'
            if row['upstream_id'] in COMMON_POLICIES:
                row['known_checker_activation_flags'] = ['-Wextra']
                row['gaps'].append('Pinned checker registration requires explicit -Wextra; factory-file section alone is not activation evidence.')
        self.assertEqual('0db373ac11f29b911317d12a51426ce690c50c3a02e327d086879541df7cc29c',
                         digest((json.dumps(before, indent=2) + '\n').encode()))
        self.assertEqual(contracts.pre_activation_mapping_bytes(ROOT), (json.dumps(before, indent=2) + '\n').encode())

    def test_current_coverage_changes_only_two_current_input_identities_and_keeps1663_by8(self):
        # Immutable1663 selection SOURCE DATA only; do not follow its historical receipt references.
        # The active1665 selection/admission successor is tested separately, never globally relabeled.
        data = contracts.legacy_source_bytes(ROOT, contracts.SELECTION_REL)
        selection = json.loads(data)
        self.assertEqual(data, (json.dumps(selection, sort_keys=True, indent=2) + '\n').encode())
        self.assertEqual({'catalog_records': 1663, 'contexts': 8, 'rows': 13304, 'withdrawn_provider_rule_context_cells': 450}, selection['scope'])
        self.assertEqual([4, 6, 5, 47], [len(selection[key]) for key in ('catalogs', 'mappings', 'selected_evidence', 'reference_files')])
        catalog_ids = [row['id'] for item in selection['catalogs']
                       for row in json.loads(contracts.historical_source_bytes(ROOT, item['path']))]
        self.assertEqual(1663, len(catalog_ids))
        self.assertEqual(1663, len(set(catalog_ids)))
        self.assertEqual(8, len(selection['contexts']))
        current_ids = [row['id'] for item in selection['catalogs'] for row in json.loads((ROOT / item['path']).read_bytes())]
        self.assertEqual((1665, 1665), (len(current_ids), len(set(current_ids))))
        self.assertEqual(set(contracts.NEW_IDS), set(current_ids) - set(catalog_ids))
        self.assertFalse(set(catalog_ids) - set(current_ids))
        old_map_pin = next(item for item in selection['mappings'] if item['path'] == MAP_REL)
        old_core_pin = next(item for item in selection['catalogs'] if item['path'] == contracts.CORE_REL)
        self.assertNotEqual(old_map_pin['sha256'], digest(self.map_bytes))
        self.assertNotEqual(old_core_pin['sha256'], digest((ROOT / contracts.CORE_REL).read_bytes()))
        for group, path, old_size, old_sha in (
            ('mappings', MAP_REL, 2609961, '0db373ac11f29b911317d12a51426ce690c50c3a02e327d086879541df7cc29c'),
            ('reference_files', FLAGS_REL, 3598, 'fb19a9de4bab6c1f2273b1d7466ce1fb4dd18fa9ca38e5e09139f12612af3b37'),
        ):
            items = [item for item in selection[group] if item['path'] == path]
            self.assertEqual(1, len(items))
            body = contracts.historical_source_bytes(ROOT, path)
            self.assertEqual((len(body), digest(body)), (items[0]['bytes'], items[0]['sha256']))
            items[0]['bytes'] = old_size
            items[0]['sha256'] = old_sha
        self.assertEqual('16cb0f8e6f021ec1fdf779399370c20f90a10567c8970a6e6346167b1aedbb2a',
                         digest((json.dumps(selection, sort_keys=True, indent=2) + '\n').encode()))

    def test_breadth_cohorts_oracles_fixtures_and35_test_bodies_are_unchanged(self):
        manifest_bytes = (FIXTURES / 'cohort-plans/diagnostic-breadth-v1.json').read_bytes()
        self.assertEqual('4bbb0528fcd0ab9b7c5c8fb5252bbe03a481666abed1ec5bb29b6632bed8b8bd', digest(manifest_bytes))
        self.assertEqual('ed4e9f5c81d0ca7383fe38b41da4d4a23e419b7afd5ecee1e178fa9ffc9b546b',
                         digest((FIXTURES / 'cohort-plans/diagnostic-breadth-source-evidence.json').read_bytes()))
        manifest = json.loads(manifest_bytes)
        files = [item for rule in manifest['fixtures'] for item in rule['inputs']]
        self.assertEqual((15, 90), (len(manifest['fixtures']), len(files)))
        for item in files:
            self.assertEqual(item['source_sha256'], digest((FIXTURES / item['path']).read_bytes()), item['path'])
        old_inventory_sha = '873914778bca5b799364cdb93e5b2bf3e8beab3c1fe544478418234b33c4cc3a'
        inventory_bytes = (FIXTURES / 'cohort-plans/diagnostic-breadth-gap-inventory.json').read_bytes()
        inventory = json.loads(inventory_bytes)
        self.assertEqual(contracts.LEGACY_MAP_SHA256, inventory['existing_compiler_registry']['sha256'])
        self.assertNotEqual(digest(self.map_bytes), inventory['existing_compiler_registry']['sha256'])
        inventory['existing_compiler_registry']['sha256'] = '0db373ac11f29b911317d12a51426ce690c50c3a02e327d086879541df7cc29c'
        self.assertEqual(old_inventory_sha, digest((json.dumps(inventory, indent=2) + '\n').encode()))
        self.assertEqual(443, len(inventory['mappings']))
        self.assertEqual({'ids': 1663, 'contexts': 8, 'cells': 13304, 'historical_stage_transfer': False, 'inventory_narrowing': False}, inventory['global_invariant'])
        self.assertTrue(all(row['new_stage_credit'] == 'none' and not row['historical_status_imported'] for row in inventory['mappings']))
        plan = (ROOT / PLAN_REL).read_text(encoding='utf-8')
        old_hook_sha = '93c29c849e3649522da4ab59fffc6a301db084cfae26545cd3736fd8c7ed434c'
        new_hook_sha = digest(contracts.legacy_fixture_bytes(ROOT, 'test_diagnostic_breadth_hookup.py'))
        self.assertEqual(1, plan.count(digest(inventory_bytes)))
        self.assertEqual(1, plan.count(new_hook_sha))
        restored_plan = plan.replace(digest(inventory_bytes), old_inventory_sha, 1).replace(new_hook_sha, old_hook_sha, 1)
        self.assertEqual('38b441f943a96032c4c9a208c1bc877b5e1f53a70a119352bdc436d58f0838a7', digest(restored_plan.encode()))
        tests = ROOT / 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureDiagnosticBreadthTest.kt'
        # Frozen35-body DATA stays exact; the active successor changes only two explicit contract tests.
        self.assertEqual('e5c67dfd4dd109f75d557052f039da6696577d277b32c8f44c469683c534c603',
                         digest(contracts.legacy_source_bytes(ROOT, tests.relative_to(ROOT).as_posix())))
        self.assertEqual(35, len(re.findall(r'@Test\s+fun\s+', tests.read_text(encoding='utf-8'))))

    def test_original325_historical_pins_and18_breadth_hookup_test_identities_remain_literal(self):
        path = FIXTURES / 'test_diagnostic_breadth_hookup.py'
        pins = literal_constant(path, 'STABLE_SOURCE_PINS')
        self.assertEqual(325, len(pins))
        self.assertEqual('f95422f6a6adb28aacb422cbf7dcd849aaa877cfa0bf05642cf048b826986479', digest(json.dumps(pins, sort_keys=True, separators=(',', ':')).encode()))
        self.assertEqual('290edd0c54eb99c6d6eed4277f6f953eb8f319e60582e1f8151186721112aecf', pins[MAP_REL])
        self.assertEqual('0db373ac11f29b911317d12a51426ce690c50c3a02e327d086879541df7cc29c',
                         literal_constant(path, 'REGISTRY_IMPLEMENTATION_REFRESH_POSTIMAGE'))
        tree = ast.parse(path.read_text(encoding='utf-8'))
        cls = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == 'DiagnosticBreadthHookupSourceTest')
        self.assertEqual(BREADTH_TEST_NAMES, [node.name for node in cls.body if isinstance(node, ast.FunctionDef) and node.name.startswith('test_')])

    def test_provider_and_protected_flag_validation_suppression_and_return_mode_bytes_are_unchanged(self):
        legacy = contracts.legacy_source_bytes(ROOT, PROVIDER_REL)
        self.assertEqual('3b631237d98703f077ac2af665c782af4213f69fdf203bd4d426f55a70274e63', digest(legacy))
        anchor = b'            "-Dfile.encoding=UTF-8", "-Duser.language=en",\n'
        addition = b'            "-Dktchecker.compiler.mapping=${registry.mappingIdentity.contractId}:${registry.mappingIdentity.resourceDigest}",\n'
        self.assertEqual(1, legacy.count(anchor))
        # Exact observation wrapping is removed before the unchanged diagnostics inverse.
        core = contracts.observation_predecessor_bytes(PROVIDER_REL, (ROOT / PROVIDER_REL).read_bytes())
        predecessor = contracts.diagnostics_predecessor_bytes(PROVIDER_REL, core)
        self.assertEqual(legacy.replace(anchor, addition + anchor, 1), predecessor)
        validate = self.flags[self.flags.index('    fun validate('):self.flags.index('    /** Disabled or unenabled')]
        prefix = self.flags[self.flags.index('    internal fun activationGap('):self.flags.index('        val commonCheckerInPinnedVersion =')]
        suffix = self.flags[self.flags.index('        if ((mapping.sourceSection.contains("return value"'):]
        self.assertEqual('10e1296e8db8ac2aad5c7733674b29151f6aba88f377d0f019fab2be57f3fc43', digest(validate.encode()))
        self.assertEqual('d6121807a6c541e5c0a11b380aa7b2d96aa97cd72d743d3b3b647304514e35fb', digest(prefix.encode()))
        self.assertEqual('4f096d4618e290bede734992f062aa3b9c93dc47805ecc03bcd2f8a3d5e2fe9e', digest(suffix.encode()))

    def test_ten_new_kotlin_readiness_controls_are_named_and_unconnected_to_execution_authority(self):
        data = (ROOT / KOTLIN_TEST_REL).read_bytes()
        self.assertEqual(KOTLIN_TEST_SHA256, digest(data))
        source = data.decode()
        names = re.findall(r'@Test\s+fun\s+(\w+)\(', source)
        self.assertEqual(10, len(names))
        self.assertEqual(10, len(set(names)))
        for required in ('fourPinnedCommonCheckersDoNotRequireExtraWarnings',
                         'explicitWarningDisableControlsStillPrecedeCommonRegistration',
                         'allFifteenActualExtraCheckersRemainFailClosedWithoutExtraWarnings',
                         'commonExceptionsDoNotGeneralizeToOtherIdsBackendsOrVersions'):
            self.assertIn(required, names)
        for forbidden in ('ProcessBuilder(', 'ExecutionAuthority(', 'ExactExecutionAuthority(', 'runFixture(', 'KotlinJvmCompilerProvider('):
            self.assertNotIn(forbidden, source)


if __name__ == '__main__':
    unittest.main()

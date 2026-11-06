#!/usr/bin/env python3
"""Affirmative445 SOURCE contracts plus immutable legacy DATA; never a compiler/provider execution test."""
from collections import Counter
from pathlib import Path
import ast
import copy
import json
import re
import tempfile
import unittest
from unittest.mock import patch

import compiler_mapping_contracts as contracts

ROOT = Path(__file__).resolve().parents[2]
FIXTURE_REL = 'providers/compiler-fixtures/'
KOTLIN_REL = 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/'
MAIN_REL = 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/'
# Whole current files are separately pinned. These closed sections alone may differ from immutable old tests.
CHANGED_PYTHON_METHODS = {'test_checker_activation.py': ['test_all443_catalog_links_policy_kinds_severities_and_backend_counts_are_preserved', 'test_all443_current_implementation_pins_and_only_four_semantic_deltas_invert_to_previous_map', 'test_current_coverage_changes_only_two_current_input_identities_and_keeps1663_by8', 'test_breadth_cohorts_oracles_fixtures_and35_test_bodies_are_unchanged', 'test_provider_and_protected_flag_validation_suppression_and_return_mode_bytes_are_unchanged'], 'test_diagnostic_breadth.py': ['test_fifteen_selected_ids_are_disjoint_existing_exact_mappings_with_preserved_activation_groups', 'test_exact_named_unmapped_operator_companion_is_required_raw_only_not_a_sixteenth_rule', 'test_1663_by_eight_global_cells_legacy_inventories_and_all_unqualified_gaps_are_not_narrowed'], 'test_diagnostic_breadth_hookup.py': ['test_all_seven_whole_file_successor_pins_and_four_data_doc_pins_bind_the_reviewed_dependency_graph', 'test_all_325_old_source_pins_keep_278_ordinary_graph_sources_30_nullability_and_existing_metadata', 'test_exact_compatibility_reconstruction_preserves_historical_identities_and_rejects_unrelated_mutations', 'test_six_exact_metadata_pins_capture_all_ninety_sources_plus_data_docs_and_both_python_controls'], 'test_nullability_protocols.py': ['test_exact_six_common_direct_error_ids_have_existing_maps_not_new_catalog_rows', 'test_bookkeeping_keeps_existing98_graph16_map443_supplemental18_and_all278_bytes_unchanged', 'test_pure_source_description_cannot_launder_thirty_counts_into_execution_or_promote_tampered_id_bytes_order', 'test_source_cli_is_read_only_and_never_prints_bodies_or_schedules_processes'], 'test_nullability_protocols_hookup.py': ['test_six_source_metadata_pins_are_exact_and_the_thirty_six_capture_paths_are_dedicated', 'test_all_historical_278_sources_main_98_graph_16_map_443_and_supplemental_18_stay_exact', 'test_exact_existing_common_error_mappings_are_used_without_an_ordinary_fixture_append'], 'test_original42_selectors.py': ['test_ten_literal_selectors_match_reviewed_ids_and_partition_exactly_original42'], 'test_type_boundaries_supplemental.py': ['test_all_98_ordinary_16_planned_443_mappings_remain_visible_without_six_kind_promotion', 'test_all_existing_fixture_and_initial_authoring_bytes_are_preserved']}
CHANGED_KOTLIN_METHODS = {'CompilerProviderTest.kt': ['exactManifestIsNotTargetCoverage'], 'CompilerCheckerActivationTest.kt': ['allCompilerMappingsAndPolicyCountsRemainVisibleWithoutPromotion'], 'CompilerFixtureDiagnosticBreadthTest.kt': ['reviewedActivationGroupsSeveritiesAndUnmappedCompanionDoNotCreateAnyNewMapping', 'ninetySourcePathsAndSixExactMetadataPinsAreAllVisibleAtStartIncludingUnselectedFiles'], 'CompilerFixtureNullabilityTest.kt': ['exactThirtySixAdditionalCapturePathsAndSixMetadataPinsRemainReadOnlySourceV1']}


# Independently fixed r3 authored SOURCE selection. These are not execution evidence,
# do not extend current389, and cannot be replaced by a locally repinned manifest.
EXPECTED_OBSERVATION_SOURCE_PINS = {'modules/cli/src/main/kotlin/dev/ktchecker/cli/Workbench.kt': {'bytes': 42659,
                                                                'sha256': 'f37d4784f760379b386e195c1f1d3115201578caf707b04e43b5da65829f16ab'},
 'modules/cli/src/test/kotlin/dev/ktchecker/cli/WorkbenchCompilerObservationTest.kt': {'bytes': 11386,
                                                                                       'sha256': 'e163f8ad7bfd385c142a13faa65dc0a923d1c6969e3704d7b8b88602829058e9'},
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerCallObservation.kt': {'bytes': 19586,
                                                                                                 'sha256': 'c825651b4f1e32f66539017c3a11e573507a27c7d99eefd73c896998c9ef7273'},
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmCompilerProvider.kt': {'bytes': 29802,
                                                                                                   'sha256': '8bfcb578f6a23203619fdc5f4257eb46580b39b65c9ae553b50e3f0a9d7dcb16'},
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerCallObservationTest.kt': {'bytes': 40044,
                                                                                                     'sha256': '2fbcff9e7b85ff94f6d946ee25134bf8da270a600768814131598fb63632b3cd'}}
EXPECTED_IMPLEMENTATION_REFS_SHA256 = '4e7c4b747d27e0b04d97df1574846a79b75fa7599faad031f7a41073ce594521'


# Independent parser/test SOURCE anchors; do not rewrite the unchanged facade5
# or either earlier inverse domain when composing this later parser-only successor.
EXPECTED_PARSER_AMBIGUITY_SOURCE_PINS = {'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerDiagnosticParser.kt': {'bytes': 17032,
                                                                                                  'sha256': '48fa09fc3f57241160b1d6d964c4a41f01edead05354afbcc5f2cde9f62a372d'},
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerHeaderSourceAmbiguityTest.kt': {'bytes': 31290,
                                                                                                           'sha256': 'e2fe6db69b6098f03cd0ef0c4acc10d3f7557aea38769859fa829ebaf6d2f51a'}}
PRE_AMBIGUITY_IMPLEMENTATION_REFS_SHA256 = 'a18f7fc2a99bbf65efc8a387841adf4c14b23e92cc156e9eea0b2a33310a2446'


def require_fixed_observation_source_inputs(root):
    contracts.require(contracts.OBSERVATION_SOURCE_PINS == EXPECTED_OBSERVATION_SOURCE_PINS,
                      'Observation selected SOURCE selector drift')
    references = [{'path': path, 'sha256': digest} for path, digest in contracts.CURRENT_IMPLEMENTATION_SOURCE_PINS.items()]
    contracts.require(contracts.digest(json.dumps(references, separators=(',', ':'), ensure_ascii=False).encode()) ==
                      EXPECTED_IMPLEMENTATION_REFS_SHA256, 'Observation implementation SOURCE selector drift')
    contracts.require_observation_source_pins(root)


def require_fixed_parser_ambiguity_source_inputs(root):
    contracts.require(contracts.PARSER_AMBIGUITY_SOURCE_PINS == EXPECTED_PARSER_AMBIGUITY_SOURCE_PINS,
                      'Parser ambiguity selected SOURCE selector drift')
    references = [{'path': path, 'sha256': digest} for path, digest in contracts.CURRENT_IMPLEMENTATION_SOURCE_PINS.items()]
    contracts.require(contracts.digest(json.dumps(references, separators=(',', ':'), ensure_ascii=False).encode()) ==
                      EXPECTED_IMPLEMENTATION_REFS_SHA256, 'Parser ambiguity implementation SOURCE selector drift')
    contracts.require_parser_ambiguity_source_pins(root)


def python_methods(source):
    tree = ast.parse(source)
    methods = [node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef) and node.name.startswith('test_')]
    if not methods or len({node.name for node in methods}) != len(methods):
        raise ValueError('Missing or duplicate active test names')
    forbidden = {'skip', 'skipIf', 'skipUnless', 'skipTest', 'expectedFailure'}
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            name = node.func.attr if isinstance(node.func, ast.Attribute) else node.func.id if isinstance(node.func, ast.Name) else ''
            if name in forbidden:
                raise ValueError('Active tests must not be skipped or disabled')
        if isinstance(node, (ast.Attribute, ast.Name)) and getattr(node, 'attr', getattr(node, 'id', '')) in forbidden:
            raise ValueError('Active tests must not be skipped or disabled')
    return {node.name: ast.get_source_segment(source, node) for node in methods}


def kotlin_test_sections(source):
    """Pinned class-level @Test sections, including adjacent unchanged helper text; not a Kotlin compiler."""
    if re.search(r'@(Ignore|Disabled)\b', source):
        raise ValueError('Active Kotlin tests must not be disabled')
    markers = list(re.finditer(r'(?m)^    @Test\s+fun\s+(\w+)\(', source))
    if not markers or len({match.group(1) for match in markers}) != len(markers):
        raise ValueError('Missing or duplicate active Kotlin test names')
    return {match.group(1): source[match.start():markers[index + 1].start() if index + 1 < len(markers) else len(source)]
            for index, match in enumerate(markers)}


def require_method_parity(before, current, changed, kotlin=False):
    reader = kotlin_test_sections if kotlin else python_methods
    old = reader(before); new = reader(current)
    if list(old) != list(new):
        raise ValueError('An existing active test was deleted, added, renamed or reordered')
    differences = {name for name in old if old[name] != new[name]}
    if differences != set(changed):
        raise ValueError('Changes outside the exact version-contract test sections')
    return len(old) - len(differences)


class CompilerMappingContractSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.current = contracts.current445(ROOT)
        cls.legacy = contracts.legacy_mapping(ROOT)
        contracts.require_current_source_pins(ROOT)
        contracts.require_legacy_runner_refusal_source(ROOT)

    def test_affirmative445_counts_exact_two_planned_catalog_additions_and_all443_semantic_rows(self):
        rows = self.current['mappings']
        self.assertEqual(contracts.CURRENT_CONTRACT, self.current['mapping_contract'])
        self.assertEqual((445, 443, 402), (len(rows), len(self.legacy['mappings']), sum(row['backend'] != 'native' for row in rows)))
        self.assertEqual({'common': 294, 'jvm': 99, 'native': 43, 'ir': 9}, dict(Counter(row['backend'] for row in rows)))
        self.assertEqual({'enabled_when_validated': 330, 'advisory': 63, 'opt_in': 52}, dict(Counter(row['default_activation'] for row in rows)))
        core_data = contracts.read_source(ROOT, contracts.CORE_REL)
        old_data = contracts.legacy_source_bytes(ROOT, contracts.CORE_REL)
        core = json.loads(core_data); old = json.loads(old_data)
        self.assertEqual((799, 797), (len(core), len(old)))
        self.assertEqual(old, core[:-2])
        self.assertEqual(contracts.NEW_IDS, tuple(row['id'] for row in core[-2:]))
        self.assertTrue(all(row['status'] == 'planned' for row in core[-2:]))
        self.assertTrue(old_data.endswith(b'\n]\n'))
        self.assertTrue(core_data.startswith(old_data[:-3] + b',\n'))
        contracts.require_mapping_parity(self.current, self.legacy)
        expected_refs = self.current['mappings'][0]['implementation_evidence']
        self.assertEqual(23, len(expected_refs))
        # Three exact implementation-reference transitions, not an open repinning rule.
        expected_changes = {
            MAIN_REL + 'CompilerRuleRegistry.kt': (
                '4acda521ed44c2c30232c2ebd47ef4ed7ecde85e589736d48a98937a0191e667',
                '02cae63a16b07c5682a32772e7867648ad4cdf01dc2f958024da620c7f1418e8'),
            MAIN_REL + 'KotlinJvmCompilerProvider.kt': (
                '3b631237d98703f077ac2af665c782af4213f69fdf203bd4d426f55a70274e63',
                '8bfcb578f6a23203619fdc5f4257eb46580b39b65c9ae553b50e3f0a9d7dcb16'),
            MAIN_REL + 'CompilerDiagnosticParser.kt': (
                'db0048e7083bfc8d1565d0505d1b3c00ad574e6e50ef2dc39797a947912e3f3e',
                '48fa09fc3f57241160b1d6d964c4a41f01edead05354afbcc5f2cde9f62a372d'),
        }
        self.assertEqual(expected_changes, contracts.REFERENCE_CHANGES)
        added = {'path': MAIN_REL + 'CompilerCallObservation.kt',
                 'sha256': 'c825651b4f1e32f66539017c3a11e573507a27c7d99eefd73c896998c9ef7273'}
        self.assertEqual([added], [ref for ref in expected_refs if ref['path'] == added['path']])
        retained = [ref for ref in expected_refs if ref['path'] != added['path']]
        self.assertEqual(22, len(retained))
        self.assertEqual(expected_changes, {
            before['path']: (before['sha256'], after['sha256'])
            for before, after in zip(self.legacy['mappings'][0]['implementation_evidence'], retained)
            if before != after
        })
        for reference in expected_refs:
            self.assertEqual(reference['sha256'], contracts.digest(contracts.read_source(ROOT, reference['path'])))

    def test_header_old_semantic_reference_and_new_contract_mutations_cannot_hide_in_the445_append(self):
        mutations = []
        for field, value in (('mapping_contract', contracts.LEGACY_CONTRACT), ('schema_version', 2), ('provider_version', '2.4.20')):
            altered = copy.deepcopy(self.current); altered[field] = value; mutations.append(altered)
        for field, value in (('relation', 'broad'), ('default_activation', 'reviewed_mutation'), ('declared_severity', 'FATAL'),
                             ('source', 'not-the-authoritative-source'), ('known_checker_activation_flags', ['-Wextra']),
                             ('catalog_record_sha256', '0' * 64), ('gaps', [])):
            altered = copy.deepcopy(self.current); altered['mappings'][0][field] = value; mutations.append(altered)
        references = self.current['mappings'][0]['implementation_evidence']
        for bad_refs in (references[:-1], references[1:], list(reversed(references)),
                         references + [references[0]],
                         references + [{'path': MAIN_REL + 'UnreviewedObservation.kt', 'sha256': '0' * 64}],
                         [references[1], references[0]] + references[2:]):
            altered = copy.deepcopy(self.current); altered['mappings'][0]['implementation_evidence'] = bad_refs; mutations.append(altered)
        for index, field, value in ((0, 'sha256', '0' * 64), (0, 'path', 'unreviewed.kt')):
            altered = copy.deepcopy(self.current); altered['mappings'][0]['implementation_evidence'][index][field] = value; mutations.append(altered)
        # No legacy, pre-diagnostics or core-v2 pre-observation reference is current.
        stale_refs = [(path, pair[0]) for path, pair in contracts.REFERENCE_CHANGES.items()]
        stale_refs.extend((MAIN_REL + 'KotlinJvmCompilerProvider.kt', value) for value in (
            '8ee53a10c3eeff45dc6dc3bfa717d7e901515a74a7998327c7380912309f7a78',
            'a8cec0f648ad66e22cf8b911283500fa3a761b56d20f2abdbf3b13e3f55d9112'))
        stale_refs.append((MAIN_REL + 'CompilerDiagnosticParser.kt',
                           'f6a1d168873a4a47120110968095e766435526fa91f75749df414253a1a5028a'))
        for path, stale in stale_refs:
            altered = copy.deepcopy(self.current)
            reference = next(ref for ref in altered['mappings'][0]['implementation_evidence'] if ref['path'] == path)
            reference['sha256'] = stale
            mutations.append(altered)
        for field, value in (('known_checker_activation_flags', ['-Wextra']), ('default_activation', 'opt_in'),
                             ('catalog_record_sha256', '0' * 64), ('source', 'not-the-declaration'), ('gaps', []),
                             ('upstream_id', 'INAPPLICABLE_OPERATOR_MODIFIER_WARNING'), ('backend', 'jvm'), ('relation', 'alias')):
            altered = copy.deepcopy(self.current); altered['mappings'][-1][field] = value; mutations.append(altered)
        altered = copy.deepcopy(self.current); altered['mappings'][-1]['rule_id'] = altered['mappings'][0]['rule_id']; mutations.append(altered)
        altered = copy.deepcopy(self.current); altered['mappings'][-1]['upstream_id'] = altered['mappings'][0]['upstream_id']; mutations.append(altered)
        altered = copy.deepcopy(self.current); altered['mappings'][0], altered['mappings'][1] = altered['mappings'][1], altered['mappings'][0]; mutations.append(altered)
        altered = copy.deepcopy(self.current); altered['mappings'][-2:] = reversed(altered['mappings'][-2:]); mutations.append(altered)
        altered = copy.deepcopy(self.current); altered['mappings'] = altered['mappings'][:-1]; mutations.append(altered)
        altered = copy.deepcopy(self.current); altered['extra_admission'] = True; mutations.append(altered)
        for index, mutation in enumerate(mutations):
            with self.subTest(index=index), self.assertRaises(ValueError):
                contracts.require_mapping_parity(mutation, self.legacy)

        # Historical authoring443 never permits either active catalog/mapping to lose a rule.
        original_reader = contracts.read_source
        for relative in (contracts.CORE_REL, contracts.MAP_REL):
            document = json.loads(original_reader(ROOT, relative))
            if relative == contracts.CORE_REL:
                document = [row for row in document if row['id'] != contracts.NEW_IDS[0]]
            else:
                document['mappings'] = [row for row in document['mappings'] if row['rule_id'] != contracts.NEW_IDS[0]]
            mutation = (json.dumps(document, indent=2) + '\n').encode()

            def read_missing_rule(root, path):
                return mutation if path == relative else original_reader(root, path)

            with patch.object(contracts, 'read_source', side_effect=read_missing_rule), self.assertRaises(ValueError):
                contracts.current445(ROOT)

    def test_four_preexisting_nullability_activation_disagreements_are_explicit_not_rewritten_or_called_current(self):
        gap_path = FIXTURE_REL + 'cohort-plans/nullability-protocols-gap-inventory.json'
        gaps = json.loads(contracts.read_source(ROOT, gap_path))['remaining_mapped_gaps']
        legacy = {row['rule_id']: row for row in self.legacy['mappings']}
        current = {row['rule_id']: row for row in self.current['mappings']}
        pre_activation = {row['rule_id']: row for row in json.loads(contracts.pre_activation_mapping_bytes(ROOT))['mappings']}
        fields = ('rule_id', 'upstream_id', 'backend', 'declared_severity', 'source_section', 'source', 'known_checker_activation_flags')
        differences = {(row['upstream_id'], field) for row in gaps for field in fields if row[field] != legacy[row['rule_id']][field]}
        names = {'ARRAY_EQUALITY_OPERATOR_CAN_BE_REPLACED_WITH_CONTENT_EQUALS', 'REDUNDANT_CALL_OF_CONVERSION_METHOD',
                 'UNUSED_EXPRESSION', 'UNUSED_LAMBDA_EXPRESSION'}
        self.assertEqual({(name, 'known_checker_activation_flags') for name in names}, differences)
        self.assertEqual(329, len(gaps))
        for row in gaps:
            self.assertEqual({field: row[field] for field in fields}, {field: pre_activation[row['rule_id']][field] for field in fields})
            self.assertEqual({field: legacy[row['rule_id']][field] for field in fields}, {field: current[row['rule_id']][field] for field in fields})
            if row['upstream_id'] in names:
                self.assertEqual(['-Wextra'], row['known_checker_activation_flags'])
                self.assertEqual([], current[row['rule_id']]['known_checker_activation_flags'])
        source = contracts.read_source(ROOT, FIXTURE_REL + 'test_nullability_protocols.py').decode()
        self.assertIn('Actual445 must refuse the unchanged443 inventory', source)
        self.assertIn("self.assertEqual('', refused_output.getvalue())", source)
        self.assertIn("if Path(directory).resolve() == ROOT and relative == contracts.MAP_REL:", source)
        self.assertIn('return original_reader(directory, relative)', source)
        self.assertIn('mapping_data = contracts.pre_activation_mapping_bytes(ROOT)', source)
        # No caller can choose replacement bytes or a legacy executable repository through this test seam.
        function = next(node for node in ast.parse(source).body if isinstance(node, ast.FunctionDef) and node.name == 'legacy_pre_activation_mapping_data')
        self.assertEqual([], function.args.args)
        self.assertIn("with patch.object(plan, 'read_existing_source', side_effect=read_with_legacy_mapping):", ast.get_source_segment(source, function))

    def test_frozen_source_data_inputs_are_closed_raw_exact_and_never_historical_receipt_replays(self):
        manifest = json.loads(contracts.read_source(ROOT, contracts.DATA_REL + '/manifest.json'))
        self.assertEqual(contracts.LEGACY_DATA_PINS, manifest['files'])
        self.assertEqual(18, len(manifest['files']))
        self.assertTrue(contracts.UNCHANGED_SOURCE_PINS)
        self.assertEqual(353, len(contracts.UNCHANGED_SOURCE_PINS))
        self.assertEqual(389, len(contracts.CURRENT_SOURCE_PINS))
        self.assertEqual('ad6943368bdff2e372f1de7226df947a0f1d7d524e614785c5375933e4746482',
                         contracts.digest(json.dumps(sorted(contracts.CURRENT_SOURCE_PINS), separators=(',', ':')).encode()))
        self.assertTrue(set(contracts.UNCHANGED_SOURCE_PINS) <= set(contracts.CURRENT_SOURCE_PINS))
        for relative, expected in contracts.UNCHANGED_SOURCE_PINS.items():
            self.assertEqual(expected, contracts.CURRENT_SOURCE_PINS[relative])
            self.assertEqual(expected, contracts.digest(contracts.historical_source_bytes(ROOT, relative)))
        # Exactly two former unchanged inputs now have independently pinned SOURCE inverses.
        self.assertEqual({
            FIXTURE_REL + 'generate_fixtures.py': '73cfab45c982081da4088daa6fc2b266f16d24c828010772966ea201d1372b69',
            FIXTURE_REL + 'test_type_boundaries.py': '6bb1a15f884b382593ee797fe4c09d0ec65399a600ffbda9574efa6c7f67456d',
        }, {relative: pin['preimage_sha256'] for relative, pin in contracts.AUTHORING_SOURCE_TRANSITIONS.items()})
        for relative, pin in contracts.AUTHORING_SOURCE_TRANSITIONS.items():
            self.assertNotIn(relative, contracts.UNCHANGED_SOURCE_PINS)
            self.assertEqual(pin['successor_sha256'], contracts.CURRENT_SOURCE_PINS[relative])
            prior = contracts.historical_source_bytes(ROOT, relative)
            self.assertEqual((pin['preimage_bytes'], pin['preimage_sha256']), (len(prior), contracts.digest(prior)))
            self.assertNotEqual(prior, contracts.read_source(ROOT, relative))
        # Diagnostics transitions are independent of the two authoring inverses.
        # The provider predecessor includes the later mapping property; its legacy
        # DATA is older still and must retain precedence in historical joins.
        expected_diagnostics = {
            MAIN_REL + 'CompilerDiagnosticParser.kt': {
                'preimage_bytes': 6408,
                'preimage_sha256': 'db0048e7083bfc8d1565d0505d1b3c00ad574e6e50ef2dc39797a947912e3f3e',
                'successor_bytes': 12953,
                'successor_sha256': 'f6a1d168873a4a47120110968095e766435526fa91f75749df414253a1a5028a',
            },
            MAIN_REL + 'KotlinJvmCompilerProvider.kt': {
                'preimage_bytes': 28750,
                'preimage_sha256': '8ee53a10c3eeff45dc6dc3bfa717d7e901515a74a7998327c7380912309f7a78',
                'successor_bytes': 29188,
                'successor_sha256': 'a8cec0f648ad66e22cf8b911283500fa3a761b56d20f2abdbf3b13e3f55d9112',
            },
        }
        self.assertEqual(set(expected_diagnostics), set(contracts.DIAGNOSTICS_SOURCE_TRANSITIONS))
        self.assertFalse(set(expected_diagnostics) & set(contracts.AUTHORING_SOURCE_TRANSITIONS))
        for relative, expected in expected_diagnostics.items():
            pin = contracts.DIAGNOSTICS_SOURCE_TRANSITIONS[relative]
            self.assertEqual(expected, {key: pin[key] for key in expected})
            self.assertNotIn(relative, contracts.UNCHANGED_SOURCE_PINS)
            current = contracts.read_source(ROOT, relative)
            if relative in contracts.OBSERVATION_SOURCE_TRANSITIONS:
                observation = contracts.OBSERVATION_SOURCE_TRANSITIONS[relative]
                self.assertEqual(pin['successor_sha256'], observation['preimage_sha256'])
                self.assertEqual(observation['successor_sha256'], contracts.CURRENT_SOURCE_PINS[relative])
                current = contracts.observation_predecessor_bytes(relative, current)
            elif relative in contracts.PARSER_AMBIGUITY_SOURCE_TRANSITIONS:
                ambiguity = contracts.PARSER_AMBIGUITY_SOURCE_TRANSITIONS[relative]
                self.assertEqual(pin['successor_sha256'], ambiguity['preimage_sha256'])
                self.assertEqual(ambiguity['successor_sha256'], contracts.CURRENT_SOURCE_PINS[relative])
                current = contracts.parser_ambiguity_predecessor_bytes(relative, current)
            else:
                self.assertEqual(pin['successor_sha256'], contracts.CURRENT_SOURCE_PINS[relative])
            self.assertEqual(pin['successor_sha256'], contracts.digest(current))
            prior = contracts.diagnostics_predecessor_bytes(relative, current)
            self.assertEqual((pin['preimage_bytes'], pin['preimage_sha256']), (len(prior), contracts.digest(prior)))
            self.assertNotEqual(prior, current)
            historical = contracts.historical_source_bytes(ROOT, relative)
            if relative in contracts.LEGACY_DATA_PINS:
                self.assertEqual(contracts.legacy_source_bytes(ROOT, relative), historical)
                self.assertNotEqual(prior, historical)
            else:
                self.assertEqual(prior, historical)
        self.assertIs(True, manifest['never_execute_or_import_txt_payloads'])
        self.assertIs(True, manifest['no_runtime_loader_or_selector_uses_this_directory'])
        for relative, pin in manifest['files'].items():
            self.assertTrue(pin['file'].endswith('.txt'))
            data = contracts.legacy_source_bytes(ROOT, relative)
            self.assertEqual((pin['bytes'], pin['sha256']), (len(data), contracts.digest(data)))
        selection = json.loads(contracts.legacy_source_bytes(ROOT, contracts.SELECTION_REL))
        self.assertEqual((1663, 8, 13304), tuple(selection['scope'][key] for key in ('catalog_records', 'contexts', 'rows')))
        self.assertNotIn(contracts.SELECTION_REL, contracts.CURRENT_SOURCE_PINS)
        self.assertNotEqual(contracts.LEGACY_MAP_SHA256, contracts.CURRENT_MAP_SHA256)
        with self.assertRaisesRegex(ValueError, 'closed legacy SOURCE'):
            contracts.legacy_source_bytes(ROOT, 'build/historical-output/receipt.json')
        with self.assertRaisesRegex(ValueError, 'closed unchanged SOURCE join'):
            contracts.historical_source_bytes(ROOT, 'build/historical-output/receipt.json')
        data_root = ROOT / contracts.DATA_REL
        expected = {pin['file'] for pin in manifest['files'].values()} | {manifest['pre_common_activation_map']['file'], 'manifest.json', 'README.md'}
        self.assertEqual(expected, {path.name for path in data_root.iterdir()})

    def test_data_digest_canonical_path_and_symlink_mutations_are_rejected_without_a_runtime_loader(self):
        relative = MAIN_REL + 'CompilerRuleRegistry.kt'
        pin = contracts.LEGACY_DATA_PINS[relative]
        with tempfile.TemporaryDirectory(prefix='compiler-source-data-unit-') as directory:
            base = Path(directory)
            target = base / contracts.DATA_REL / pin['file']
            target.parent.mkdir(parents=True)
            target.write_bytes(contracts.legacy_source_bytes(ROOT, relative))
            self.assertEqual(target.read_bytes(), contracts.legacy_source_bytes(base, relative))
            target.write_bytes(target.read_bytes() + b'\n')
            with self.assertRaisesRegex(ValueError, 'Legacy SOURCE data drift'):
                contracts.legacy_source_bytes(base, relative)
            actual = base / 'source.kt'; actual.write_bytes(b'fun unit() = 1\n')
            (base / 'alias.kt').symlink_to(actual)
            for invalid in ('../source.kt', '/source.kt', './source.kt', 'a//b', 'C:/source.kt', 'a\\b', 'a/\x00', '', 'alias.kt'):
                with self.subTest(path=invalid), self.assertRaises(ValueError):
                    contracts.read_source(base, invalid)
            self.assertEqual(actual.read_bytes(), contracts.read_source(base, 'source.kt'))
            unchanged_relative = KOTLIN_REL + 'CompilerFixtureProvenance.kt'
            joined = base / unchanged_relative
            joined.parent.mkdir(parents=True, exist_ok=True)
            joined.write_bytes(contracts.read_source(ROOT, unchanged_relative))
            self.assertEqual(joined.read_bytes(), contracts.historical_source_bytes(base, unchanged_relative))
            joined.write_bytes(joined.read_bytes() + b'\n')
            with self.assertRaisesRegex(ValueError, 'Unchanged SOURCE join drift'):
                contracts.historical_source_bytes(base, unchanged_relative)

        # The exact postimage AND independently frozen full preimage must hold.
        # These byte-only controls never import or execute the source payloads.
        with tempfile.TemporaryDirectory(prefix='authoring-source-inverse-unit-') as directory:
            base = Path(directory)
            for relative, pin in contracts.AUTHORING_SOURCE_TRANSITIONS.items():
                data = contracts.read_source(ROOT, relative)
                prior = contracts.historical_source_bytes(ROOT, relative)
                target = base / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                self.assertEqual(prior, contracts.historical_source_bytes(base, relative))
                mutations = [prior, data + b'\n', data + b'\n# unrelated body change\n']
                if relative.endswith('/generate_fixtures.py'):
                    mutations.extend([
                        data.replace(b'LEGACY_AUTHORED_CATALOG_MAPPINGS = 443', b'LEGACY_AUTHORED_CATALOG_MAPPINGS = 444'),
                        data.replace(b"if r['id'].startswith('ktc.compiler.')", b"if r['id'].startswith('ktc.compiler.') and r['upstream_id'] != 'ERROR_SUPPRESSION'"),
                        data.replace(b'json.dumps(inventory, indent=2)', b'json.dumps({}, indent=2)'),
                    ])
                else:
                    mutations.extend([
                        data.replace(b'self.assertEqual((FIXTURES / relative).read_bytes(), content)', b'self.assertEqual(content, content)'),
                        data.replace(b"self.assertEqual(443, inventory['catalog_mappings'])", b"self.assertEqual(445, inventory['catalog_mappings'])"),
                    ])
                for mutation in mutations:
                    self.assertNotEqual(data, mutation)
                    target.write_bytes(mutation)
                    with self.assertRaises(ValueError):
                        contracts.historical_source_bytes(base, relative)
                    # Even a blind successor-hash refresh cannot waive the original full preimage.
                    with patch.dict(pin, {'successor_bytes': len(mutation), 'successor_sha256': contracts.digest(mutation)}):
                        with self.assertRaises(ValueError):
                            contracts.historical_source_bytes(base, relative)
                target.unlink()
                with self.assertRaises(ValueError):
                    contracts.historical_source_bytes(base, relative)
            with self.assertRaisesRegex(ValueError, 'closed authoring SOURCE transition'):
                contracts.authoring_predecessor_bytes('unreviewed.py', b'not a source successor')

        # Separate diagnostics SOURCE inverses reject every missing/duplicate span,
        # unrelated changes and blind successor repins without executing Kotlin.
        with tempfile.TemporaryDirectory(prefix='diagnostics-source-inverse-unit-') as directory:
            base = Path(directory)
            for relative, pin in contracts.DIAGNOSTICS_SOURCE_TRANSITIONS.items():
                source_successor = contracts.read_source(ROOT, relative)
                data = source_successor
                if relative in contracts.OBSERVATION_SOURCE_TRANSITIONS:
                    data = contracts.observation_predecessor_bytes(relative, data)
                elif relative in contracts.PARSER_AMBIGUITY_SOURCE_TRANSITIONS:
                    data = contracts.parser_ambiguity_predecessor_bytes(relative, data)
                prior = contracts.diagnostics_predecessor_bytes(relative, data)
                target = base / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                self.assertEqual(prior, contracts.diagnostics_predecessor_bytes(relative, contracts.read_source(base, relative)))
                if relative not in contracts.LEGACY_DATA_PINS:
                    # Historical reads require the actual latest SOURCE, not f6a accepted as current.
                    target.write_bytes(source_successor)
                    self.assertEqual(prior, contracts.historical_source_bytes(base, relative))
                    target.write_bytes(data)  # Exercise the unchanged diagnostics inverse on its own f6a stage.
                mutations = [prior, data + b'\n',
                             data.replace(b'package dev.ktchecker.compiler', b'package dev.ktchecker.compiler_changed', 1)]
                for successor, predecessor in pin['reverse_changes']:
                    self.assertTrue(successor)
                    self.assertEqual(1, data.count(successor))
                    mutations.extend([data.replace(successor, b'', 1), data + successor])
                for index, mutation in enumerate(mutations):
                    with self.subTest(relative=relative, mutation=index):
                        self.assertNotEqual(data, mutation)
                        target.write_bytes(mutation)
                        with self.assertRaisesRegex(ValueError, 'Diagnostics SOURCE successor drift'):
                            contracts.diagnostics_predecessor_bytes(relative, contracts.read_source(base, relative))
                        # The independent complete predecessor still cannot be waived.
                        with patch.dict(pin, {'successor_bytes': len(mutation), 'successor_sha256': contracts.digest(mutation)}):
                            with self.assertRaises(ValueError):
                                contracts.diagnostics_predecessor_bytes(relative, contracts.read_source(base, relative))
                reverse = pin['reverse_changes']
                changed_inverse = ((reverse[0][0], reverse[0][1] + b'\n// unrelated inverse change\n'),) + reverse[1:]
                with patch.dict(pin, {'reverse_changes': changed_inverse}):
                    with self.assertRaisesRegex(ValueError, 'Diagnostics SOURCE preimage drift'):
                        contracts.diagnostics_predecessor_bytes(relative, data)
                target.unlink()
                with self.assertRaises(ValueError):
                    contracts.diagnostics_predecessor_bytes(relative, contracts.read_source(base, relative))
                target.symlink_to(ROOT / relative)
                with self.assertRaisesRegex(ValueError, 'Symlink SOURCE input'):
                    contracts.diagnostics_predecessor_bytes(relative, contracts.read_source(base, relative))
                target.unlink()
            for relative in ('unreviewed.kt', MAIN_REL + '../compiler/CompilerDiagnosticParser.kt',
                             FIXTURE_REL + 'generate_fixtures.py'):
                with self.assertRaisesRegex(ValueError, 'closed diagnostics SOURCE transition'):
                    contracts.diagnostics_predecessor_bytes(relative, b'not a source successor')

    def test_helper_is_read_only_stdlib_and_runtime_sources_have_no_legacy_data_loader_or_selector(self):
        helper = contracts.read_source(ROOT, FIXTURE_REL + 'compiler_mapping_contracts.py').decode()
        tree = ast.parse(helper)
        imported = {alias.name for node in ast.walk(tree) if isinstance(node, ast.Import) for alias in node.names}
        imported |= {node.module for node in ast.walk(tree) if isinstance(node, ast.ImportFrom)}
        self.assertEqual({'pathlib', 'hashlib', 'json'}, imported)
        self.assertFalse(any(isinstance(node, ast.Assert) for node in ast.walk(tree)))
        for node in ast.walk(tree):
            if isinstance(node, ast.Call):
                if isinstance(node.func, ast.Name):
                    self.assertNotIn(node.func.id, {'eval', 'exec', 'compile', '__import__', 'open'})
                if isinstance(node.func, ast.Attribute):
                    self.assertNotIn(node.func.attr, {'write_bytes', 'write_text', 'mkdir', 'unlink', 'Popen', 'run', 'execute', 'analyze', 'analyzeObserved'})
        for relative in [ref['path'] for ref in self.current['mappings'][0]['implementation_evidence']] + [KOTLIN_REL + 'CompilerFixtureRunner.kt']:
            text = contracts.read_source(ROOT, relative).decode()
            self.assertNotIn('CompilerLegacy443SourceData', text)
            self.assertNotIn('mapping-contracts/legacy443-data', text)
        test_helper = contracts.read_source(ROOT, KOTLIN_REL + 'CompilerLegacy443SourceData.kt').decode()
        self.assertIn('Not a closed legacy SOURCE metadata input', test_helper)
        for forbidden in ('CompilerRuleRegistry.parse(', 'KotlinJvmCompilerProvider(', 'ProcessBuilder(', 'ExecutionPermit(', 'Files.write', 'Files.create'):
            self.assertNotIn(forbidden, test_helper)

    def test_registry_strict_byte_identity_and_profile_property_precede_preparation_and_legacy_runner_work(self):
        registry = contracts.read_source(ROOT, MAIN_REL + 'CompilerRuleRegistry.kt').decode()
        for expression in ('val frozen = bytes.copyOf()', 'onMalformedInput(CodingErrorAction.REPORT)',
                           'onUnmappableCharacter(CodingErrorAction.REPORT)', 'decode(ByteBuffer.wrap(frozen))',
                           'CompilerMappingIdentity(MAPPING_CONTRACT, Digests.sha256(frozen))',
                           'require(root.string("mapping_contract") == MAPPING_CONTRACT)', 'require(mappings.size == 445)'):
            self.assertIn(expression, registry)
        self.assertNotIn(contracts.CURRENT_MAP_SHA256, registry)  # No resource/main-source provenance hash cycle.
        provider = contracts.read_source(ROOT, MAIN_REL + 'KotlinJvmCompilerProvider.kt').decode()
        property_ = '"-Dktchecker.compiler.mapping=${registry.mappingIdentity.contractId}:${registry.mappingIdentity.resourceDigest}",'
        self.assertEqual(1, provider.count(property_))
        self.assertIn('if (execution != expectedProfile || permit.execution != execution) return unavailable("execution_profile_mismatch")', provider)
        self.assertLess(provider.index('return unavailable("execution_profile_mismatch")'), provider.index('val sources: List<SealedCompilerSource>'))
        runner = contracts.read_source(ROOT, KOTLIN_REL + 'CompilerFixtureRunner.kt').decode()
        gate = 'CompilerFixtureMappingContract.requireLegacy(CompilerRuleRegistry.load().mappingIdentity)'
        self.assertEqual(1, runner.count(gate))
        for after in ('val suppliedFixtureRoot = Path.of(', 'runRoot = compilerFixtureCreateRunDirectory',
                      'budget = CompilerFixtureBudget(', 'toolchain = KotlinJvmToolchain(', 'budget.reserveAttempt('):
            self.assertLess(runner.index(gate), runner.index(after))
        self.assertIn('run(args, probeMode = true)', runner)
        self.assertIn('require(registry.mappings.none { it.upstreamId == "INAPPLICABLE_OPERATOR_MODIFIER" })', runner)
        controls = contracts.read_source(ROOT, KOTLIN_REL + 'CompilerFixtureControls.kt').decode()
        self.assertNotIn('diagnostic-gaps-445', controls)
        self.assertNotIn('diagnostic_gaps_445', controls)
        unit = contracts.read_source(ROOT, KOTLIN_REL + 'CompilerMappingContractTest.kt').decode()
        for named in ('identityBindsExactLoadedBytesNotOnlyCountsOrSemanticRows', 'missingForeignContractWrongScopeAndMalformedUtf8CannotLoadAs445',
                      'legacyAndSuccessorIdentityCannotBeRelabeledInEitherDirection', 'everyExistingRunnerSelectorRefuses445BeforeEvenResolvingOrAllocatingPaths',
                      'mappingByteIdentityBindsProfileAnalysisKeyAndDeniesAnOlderExactPermitBeforeSnapshotOrRunner'):
            self.assertIn('@Test fun ' + named + '(', unit)
        self.assertIn('assertFalse(snapshotChecked)', unit)
        self.assertIn('assertNotEquals(prepared.analysisKey(), input.copy(execution = after).analysisKey())', unit)

    def test_all93_old_python_methods_and_kotlin_rosters_remain_active_with_only_closed_version_contract_changes(self):
        unchanged_python = 0
        for basename, changed in CHANGED_PYTHON_METHODS.items():
            relative = FIXTURE_REL + basename
            old = contracts.legacy_source_bytes(ROOT, relative).decode()
            current = contracts.read_source(ROOT, relative).decode()
            unchanged_python += require_method_parity(old, current, changed)
            self.assertIn('contracts.current445(ROOT)', current)
            self.assertIn('contracts.require_legacy_runner_refusal_source(ROOT)', current)
        # One further named method now compares exact legacy authoring SOURCE inverses.
        self.assertEqual(71, unchanged_python)
        for basename, changed in CHANGED_KOTLIN_METHODS.items():
            relative = KOTLIN_REL + basename
            old = contracts.legacy_source_bytes(ROOT, relative).decode()
            current = contracts.read_source(ROOT, relative).decode()
            unchanged = require_method_parity(old, current, changed, kotlin=True)
            if basename == 'CompilerFixtureDiagnosticBreadthTest.kt':
                self.assertEqual(33, unchanged)
            if basename == 'CompilerFixtureNullabilityTest.kt':
                self.assertEqual(19, unchanged)
        # Frozen old metadata pins still reject the actual successors, not quietly repin those old oracles.
        for basename in ('CompilerFixtureDiagnosticBreadthTest.kt', 'CompilerFixtureNullabilityTest.kt'):
            current = contracts.read_source(ROOT, KOTLIN_REL + basename).decode()
            self.assertIn('assertFails { plan.verifyCapturedMetadata(::fixtureText) }', current)
            self.assertIn('CompilerLegacy443SourceData.metadataText', current)

    def test_section_parity_controls_reject_deletions_renames_disabling_and_unrelated_body_mutations(self):
        old = 'class Unit:\n    def test_kept(self):\n        self.assertEqual(1, 1)\n    def test_versioned(self):\n        self.assertEqual(1, 1)\n'
        current = old.rsplit('self.assertEqual(1, 1)', 1)[0] + 'self.assertEqual(2, 2)\n'
        self.assertEqual(1, require_method_parity(old, current, {'test_versioned'}))
        for wrong in (current.replace('test_kept', 'removed'), current.replace('self.assertEqual(1, 1)', 'self.assertEqual(0, 0)'),
                      current.replace('    def test_kept', '    @unittest.skip("not allowed")\n    def test_kept'),
                      current.replace('self.assertEqual(1, 1)', 'self.skipTest("not allowed")'), old):
            with self.assertRaises(ValueError):
                require_method_parity(old, wrong, {'test_versioned'})
        old_kotlin = 'class Unit {\n    @Test fun kept() { assertEquals(1, 1) }\n    @Test fun versioned() { assertEquals(1, 1) }\n}\n'
        new_kotlin = old_kotlin.replace('versioned() { assertEquals(1, 1)', 'versioned() { assertEquals(2, 2)')
        self.assertEqual(1, require_method_parity(old_kotlin, new_kotlin, {'versioned'}, kotlin=True))
        for wrong in (new_kotlin.replace('kept()', 'renamed()'), new_kotlin.replace('kept() { assertEquals(1, 1)', 'kept() { assertEquals(0, 0)'),
                      new_kotlin.replace('@Test fun kept', '@Ignore @Test fun kept'), old_kotlin):
            with self.assertRaises(ValueError):
                require_method_parity(old_kotlin, wrong, {'versioned'}, kotlin=True)

    def test_new445_source_plan_is_unbound_unadmitted_and_cannot_join_any_legacy_execution_schedule(self):
        relative = FIXTURE_REL + 'cohort-plans/diagnostic-gaps-445-source-v1.json'
        plan = json.loads(contracts.read_source(ROOT, relative))
        self.assertEqual('private_candidate_unadmitted_unrun', plan['status'])
        binding = plan['mapping_contract_requirement']
        self.assertEqual(contracts.CURRENT_CONTRACT, binding['proposed_contract_id'])
        self.assertEqual((445, 402), (binding['expected_mapping_count'], binding['expected_jvm_applicable_mapping_count']))
        self.assertIsNone(binding['expected_resource_sha256'])
        self.assertIsNone(binding['expected_adopted_catalog_sha256'])
        self.assertIs(False, binding['current443_resource_is_compatible'])
        self.assertIs(True, binding['missing_binding_must_deny_before_any_provider_or_child_call'])
        self.assertIs(False, plan['ordered_cases_are_execution_authority'])
        self.assertIs(False, plan['ordinary_six_kind_assessment'])
        self.assertIsNone(plan['current_report_scope_change'])
        self.assertEqual('not_authored_or_admitted_by_this_source_plan', plan['budgets_permits_control_jobs_and_runtime_hookup'])
        self.assertEqual((9, 8), (len(plan['cases']), len({case['path'] for case in plan['cases']})))
        for case in plan['cases']:
            data = contracts.read_source(ROOT, case['path'])
            self.assertEqual((case['source_bytes'], case['source_sha256']), (len(data), contracts.digest(data)))
            self.assertEqual(list(contracts.NEW_IDS), case['selected_rule_ids'])
            self.assertEqual('not_run', case['execution_state'])
            self.assertIsNone(case['expected_outcome_matched'])
            self.assertIs(False, case['counts_as_current_test_or_implementation_evidence'])
            if case['hard_error_suppression_observation_only']:
                self.assertEqual(('unknown', 'unknown'), (case['compiler_exit_expectation'], case['provider_status_expectation']))
                operator = next(row for row in case['source_derived_expectations'] if row['upstream_id'] == 'INAPPLICABLE_OPERATOR_MODIFIER')
                self.assertIsNone(operator['expected_full_parser_count'])
                self.assertIsNone(operator['expected_selected_finding_count'])
        for path in ('CompilerFixtureRunner.kt', 'CompilerFixtureControls.kt', 'CompilerFixtureDiagnosticBreadth.kt', 'CompilerFixtureNullability.kt'):
            text = contracts.read_source(ROOT, KOTLIN_REL + path).decode()
            self.assertNotIn(plan['source_plan_id'], text)
            self.assertNotIn('diagnostic-gaps-445-cases', text)

    def test_observation_successor23_and_five_source_inputs_stay_separate_from_historical389(self):
        self.assertEqual('f8566bded002ebeb09a047924036770dbefaa687950355cb08d7174e5f82a2de', contracts.CURRENT_MAP_SHA256)
        references = contracts.current_implementation_references(ROOT)
        self.assertEqual(23, len(references))
        self.assertEqual(list(contracts.CURRENT_IMPLEMENTATION_SOURCE_PINS), sorted(contracts.CURRENT_IMPLEMENTATION_SOURCE_PINS))
        self.assertEqual(EXPECTED_IMPLEMENTATION_REFS_SHA256,
                         contracts.digest(json.dumps(references, separators=(',', ':'), ensure_ascii=False).encode()))
        self.assertTrue(all(row['implementation_evidence'] == references for row in self.current['mappings']))
        self.assertEqual(EXPECTED_OBSERVATION_SOURCE_PINS, contracts.OBSERVATION_SOURCE_PINS)
        self.assertEqual(5, len(EXPECTED_OBSERVATION_SOURCE_PINS))
        self.assertEqual({MAIN_REL + 'KotlinJvmCompilerProvider.kt'},
                         set(EXPECTED_OBSERVATION_SOURCE_PINS) & set(contracts.CURRENT_SOURCE_PINS))
        self.assertEqual(22, len(set(contracts.CURRENT_IMPLEMENTATION_SOURCE_PINS) & set(contracts.CURRENT_SOURCE_PINS)))
        self.assertEqual(389, len(contracts.CURRENT_SOURCE_PINS))
        self.assertEqual('ad6943368bdff2e372f1de7226df947a0f1d7d524e614785c5375933e4746482',
                         contracts.digest(json.dumps(sorted(contracts.CURRENT_SOURCE_PINS), separators=(',', ':')).encode()))
        self.assertNotIn(contracts.SELECTION_REL, contracts.OBSERVATION_SOURCE_PINS)
        self.assertNotIn(contracts.MAP_REL, contracts.OBSERVATION_SOURCE_PINS)
        require_fixed_observation_source_inputs(ROOT)
        for path, pin in EXPECTED_OBSERVATION_SOURCE_PINS.items():
            data = contracts.read_source(ROOT, path)
            self.assertEqual((pin['bytes'], pin['sha256']), (len(data), contracts.digest(data)))
            if path in contracts.CURRENT_IMPLEMENTATION_SOURCE_PINS:
                self.assertEqual(pin['sha256'], contracts.CURRENT_IMPLEMENTATION_SOURCE_PINS[path])
            if path != MAIN_REL + 'KotlinJvmCompilerProvider.kt':
                self.assertNotIn(path, contracts.UNCHANGED_SOURCE_PINS)
                with self.assertRaisesRegex(ValueError, 'closed unchanged SOURCE join'):
                    contracts.historical_source_bytes(ROOT, path)

    def test_observation_inventory_and_payload_mutations_cannot_be_repinned_as_current(self):
        expected_refs = self.current['mappings'][0]['implementation_evidence']
        with tempfile.TemporaryDirectory(prefix='observation-source23-unit-') as directory:
            base = Path(directory)
            for path in contracts.CURRENT_IMPLEMENTATION_SOURCE_PINS:
                target = base / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(contracts.read_source(ROOT, path))
            self.assertEqual(expected_refs, contracts.current_implementation_references(base))
            for path in contracts.CURRENT_IMPLEMENTATION_SOURCE_PINS:
                target = base / path
                data = target.read_bytes()
                target.unlink()
                with self.subTest(path=path, mutation='missing'), self.assertRaisesRegex(ValueError, 'SOURCE roster changed'):
                    contracts.current_implementation_references(base)
                target.write_bytes(data + b'\n// unreviewed source drift\n')
                with self.subTest(path=path, mutation='bytes'), self.assertRaisesRegex(ValueError, 'SOURCE bytes drift'):
                    contracts.current_implementation_references(base)
                target.write_bytes(data)
            original = base / MAIN_REL / 'CompilerCallObservation.kt'
            renamed = original.with_name('UnreviewedObservation.kt')
            original.rename(renamed)  # Same23 count is still the wrong closed path set.
            with self.assertRaisesRegex(ValueError, 'SOURCE roster changed'):
                contracts.current_implementation_references(base)
            renamed.rename(original)
            extra = base / MAIN_REL / 'UnreviewedObservation.kt'
            extra.write_bytes(b'package dev.ktchecker.compiler\n')
            with self.assertRaisesRegex(ValueError, 'SOURCE roster changed'):
                contracts.current_implementation_references(base)
            extra.unlink()
            data = original.read_bytes(); original.unlink(); original.symlink_to(ROOT / MAIN_REL / 'CompilerCallObservation.kt')
            with self.assertRaisesRegex(ValueError, 'Symlink SOURCE input'):
                contracts.current_implementation_references(base)
            original.unlink(); original.write_bytes(data)
            self.assertEqual(expected_refs, contracts.current_implementation_references(base))

        with tempfile.TemporaryDirectory(prefix='observation-selected-source-unit-') as directory:
            base = Path(directory)
            for path in EXPECTED_OBSERVATION_SOURCE_PINS:
                target = base / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(contracts.read_source(ROOT, path))
            require_fixed_observation_source_inputs(base)
            for path in EXPECTED_OBSERVATION_SOURCE_PINS:
                target = base / path
                data = target.read_bytes()
                mutations = [data + b'\n// unreviewed selected source\n']
                if path == MAIN_REL + 'CompilerCallObservation.kt':
                    # Independent whole-file anchors, not a token-based acceptance classifier.
                    mutations.extend([
                        data.replace(b'        else -> false\n    }\n}\n\nprivate fun publicReasons',
                                     b'        else -> true\n    }\n}\n\nprivate fun publicReasons', 1),
                        data.replace(b'    private val activeState = Call.Active\n', b'', 1).replace(
                            b'Call.Waiting, activeState', b'Call.Waiting, Call.Active', 1),
                    ])
                elif path == MAIN_REL + 'KotlinJvmCompilerProvider.kt':
                    mutations.append(data.replace(b'capture?.captureOutcomes(ran, outcome, supportedRuleIds)',
                                                  b'capture?.captureOutcomes(outcome, ran, supportedRuleIds)', 1))
                elif path == 'modules/cli/src/main/kotlin/dev/ktchecker/cli/Workbench.kt':
                    mutations.append(data.replace(b'if (observation == null) provider else',
                        b'if (observation == null) CompilerCallObservation().observedProvider(provider, ownerCodeDigest) else', 1))
                else:
                    mutations.append(data.replace(b'@Test ', b'@Disabled @Test ', 1))
                for index, mutation in enumerate(mutations):
                    with self.subTest(path=path, mutation=index):
                        self.assertNotEqual(data, mutation)
                        target.write_bytes(mutation)
                        with self.assertRaisesRegex(ValueError, 'Observation selected SOURCE drift'):
                            require_fixed_observation_source_inputs(base)
                        # Rewriting prospective selection metadata cannot rewrite the fixed r3 anchor.
                        repin = {'bytes': len(mutation), 'sha256': contracts.digest(mutation)}
                        with patch.dict(contracts.OBSERVATION_SOURCE_PINS, {path: repin}):
                            with self.assertRaisesRegex(ValueError, 'Observation selected SOURCE selector drift'):
                                require_fixed_observation_source_inputs(base)
                    target.write_bytes(data)
                target.unlink()
                with self.assertRaises(ValueError):
                    require_fixed_observation_source_inputs(base)
                target.symlink_to(ROOT / path)
                with self.assertRaisesRegex(ValueError, 'Symlink SOURCE input'):
                    require_fixed_observation_source_inputs(base)
                target.unlink(); target.write_bytes(data)
            require_fixed_observation_source_inputs(base)
            path = MAIN_REL + 'CompilerCallObservation.kt'
            with patch.dict(contracts.CURRENT_IMPLEMENTATION_SOURCE_PINS, {path: '0' * 64}):
                with self.assertRaisesRegex(ValueError, 'Observation implementation SOURCE selector drift'):
                    require_fixed_observation_source_inputs(base)
            with patch.dict(contracts.OBSERVATION_SOURCE_PINS, {'unreviewed.kt': {'bytes': 0, 'sha256': '0' * 64}}):
                with self.assertRaisesRegex(ValueError, 'Observation selected SOURCE selector drift'):
                    require_fixed_observation_source_inputs(base)

    def test_observation_inverses_restore_exact_core_and_workbench_without_broadening_history(self):
        expected = {
            MAIN_REL + 'KotlinJvmCompilerProvider.kt': {
                'preimage_bytes': 29188,
                'preimage_sha256': 'a8cec0f648ad66e22cf8b911283500fa3a761b56d20f2abdbf3b13e3f55d9112',
                'successor_bytes': 29802,
                'successor_sha256': '8bfcb578f6a23203619fdc5f4257eb46580b39b65c9ae553b50e3f0a9d7dcb16',
            },
            'modules/cli/src/main/kotlin/dev/ktchecker/cli/Workbench.kt': {
                'preimage_bytes': 41976,
                'preimage_sha256': '011a88a770055032d7ce07513d8c05c5aa4fa3f6a5fd743385a44bf25bd38209',
                'successor_bytes': 42659,
                'successor_sha256': 'f37d4784f760379b386e195c1f1d3115201578caf707b04e43b5da65829f16ab',
            },
        }
        self.assertEqual(set(expected), set(contracts.OBSERVATION_SOURCE_TRANSITIONS))
        self.assertEqual({MAIN_REL + 'KotlinJvmCompilerProvider.kt'},
                         set(expected) & set(contracts.DIAGNOSTICS_SOURCE_TRANSITIONS))
        self.assertFalse(set(expected) & set(contracts.AUTHORING_SOURCE_TRANSITIONS))
        with tempfile.TemporaryDirectory(prefix='observation-source-inverse-unit-') as directory:
            base = Path(directory)
            for path, fixed in expected.items():
                pin = contracts.OBSERVATION_SOURCE_TRANSITIONS[path]
                self.assertEqual(fixed, {key: pin[key] for key in fixed})
                self.assertEqual(4, len(pin['reverse_changes']))
                data = contracts.read_source(ROOT, path)
                prior = contracts.observation_predecessor_bytes(path, data)
                self.assertEqual((fixed['preimage_bytes'], fixed['preimage_sha256']), (len(prior), contracts.digest(prior)))
                if path == MAIN_REL + 'KotlinJvmCompilerProvider.kt':
                    with self.assertRaisesRegex(ValueError, 'Diagnostics SOURCE successor drift'):
                        contracts.diagnostics_predecessor_bytes(path, data)
                    intermediate = contracts.diagnostics_predecessor_bytes(path, prior)
                    self.assertEqual('8ee53a10c3eeff45dc6dc3bfa717d7e901515a74a7998327c7380912309f7a78', contracts.digest(intermediate))
                    historical = contracts.historical_source_bytes(ROOT, path)
                    self.assertEqual(contracts.legacy_source_bytes(ROOT, path), historical)
                    self.assertEqual('3b631237d98703f077ac2af665c782af4213f69fdf203bd4d426f55a70274e63', contracts.digest(historical))
                    self.assertNotEqual(intermediate, historical)
                target = base / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                self.assertEqual(prior, contracts.observation_predecessor_bytes(path, contracts.read_source(base, path)))
                mutations = [prior, data + b'\n', data + b'\n// unrelated observation mutation\n']
                for successor, predecessor in pin['reverse_changes']:
                    self.assertTrue(successor)
                    self.assertEqual(1, data.count(successor))
                    mutations.extend([data.replace(successor, b'', 1), data + successor])
                for index, mutation in enumerate(mutations):
                    with self.subTest(path=path, mutation=index):
                        self.assertNotEqual(data, mutation)
                        target.write_bytes(mutation)
                        with self.assertRaisesRegex(ValueError, 'Observation SOURCE successor drift'):
                            contracts.observation_predecessor_bytes(path, contracts.read_source(base, path))
                        with patch.dict(pin, {'successor_bytes': len(mutation), 'successor_sha256': contracts.digest(mutation)}):
                            with self.assertRaises(ValueError):
                                contracts.observation_predecessor_bytes(path, contracts.read_source(base, path))
                reverse = pin['reverse_changes']
                changed = ((reverse[0][0], reverse[0][1] + b'\n// unrelated inverse change\n'),) + reverse[1:]
                with patch.dict(pin, {'reverse_changes': changed}):
                    with self.assertRaisesRegex(ValueError, 'Observation SOURCE preimage drift'):
                        contracts.observation_predecessor_bytes(path, data)
                target.unlink()
                with self.assertRaises(ValueError):
                    contracts.observation_predecessor_bytes(path, contracts.read_source(base, path))
                target.symlink_to(ROOT / path)
                with self.assertRaisesRegex(ValueError, 'Symlink SOURCE input'):
                    contracts.observation_predecessor_bytes(path, contracts.read_source(base, path))
                target.unlink()
            for path in ('unreviewed.kt', MAIN_REL + '../compiler/KotlinJvmCompilerProvider.kt',
                         MAIN_REL + 'CompilerCallObservation.kt', MAIN_REL + 'CompilerDiagnosticParser.kt',
                         FIXTURE_REL + 'generate_fixtures.py'):
                with self.assertRaisesRegex(ValueError, 'closed observation SOURCE transition'):
                    contracts.observation_predecessor_bytes(path, b'not a source successor')



    def test_parser_ambiguity_two_source_inputs_and_exact_predecessor_keep_historical_rosters(self):
        parser = MAIN_REL + 'CompilerDiagnosticParser.kt'
        source_test = KOTLIN_REL + 'CompilerHeaderSourceAmbiguityTest.kt'
        self.assertEqual({parser, source_test}, set(EXPECTED_PARSER_AMBIGUITY_SOURCE_PINS))
        self.assertEqual(EXPECTED_PARSER_AMBIGUITY_SOURCE_PINS, contracts.PARSER_AMBIGUITY_SOURCE_PINS)
        self.assertEqual({parser}, set(contracts.PARSER_AMBIGUITY_SOURCE_TRANSITIONS))
        self.assertEqual({parser}, set(EXPECTED_PARSER_AMBIGUITY_SOURCE_PINS) & set(contracts.CURRENT_SOURCE_PINS))
        self.assertFalse(set(EXPECTED_PARSER_AMBIGUITY_SOURCE_PINS) & set(contracts.OBSERVATION_SOURCE_PINS))
        self.assertFalse(set(EXPECTED_PARSER_AMBIGUITY_SOURCE_PINS) & set(contracts.UNCHANGED_SOURCE_PINS))
        self.assertFalse(set(EXPECTED_PARSER_AMBIGUITY_SOURCE_PINS) & set(contracts.LEGACY_DATA_PINS))
        self.assertEqual((389, 353, 18, 2, 2, 2), tuple(len(value) for value in (
            contracts.CURRENT_SOURCE_PINS, contracts.UNCHANGED_SOURCE_PINS, contracts.LEGACY_DATA_PINS,
            contracts.AUTHORING_SOURCE_TRANSITIONS, contracts.DIAGNOSTICS_SOURCE_TRANSITIONS,
            contracts.OBSERVATION_SOURCE_TRANSITIONS)))
        self.assertEqual('ad6943368bdff2e372f1de7226df947a0f1d7d524e614785c5375933e4746482',
                         contracts.digest(json.dumps(sorted(contracts.CURRENT_SOURCE_PINS), separators=(',', ':')).encode()))
        require_fixed_parser_ambiguity_source_inputs(ROOT)
        references = contracts.current_implementation_references(ROOT)
        self.assertEqual(23, len(references))
        self.assertEqual(EXPECTED_IMPLEMENTATION_REFS_SHA256,
                         contracts.digest(json.dumps(references, separators=(',', ':'), ensure_ascii=False).encode()))
        before = [dict(ref, sha256='f6a1d168873a4a47120110968095e766435526fa91f75749df414253a1a5028a')
                  if ref['path'] == parser else dict(ref) for ref in references]
        self.assertEqual(PRE_AMBIGUITY_IMPLEMENTATION_REFS_SHA256,
                         contracts.digest(json.dumps(before, separators=(',', ':'), ensure_ascii=False).encode()))
        fixed = {'preimage_bytes': 12953,
                 'preimage_sha256': 'f6a1d168873a4a47120110968095e766435526fa91f75749df414253a1a5028a',
                 'successor_bytes': 17032,
                 'successor_sha256': '48fa09fc3f57241160b1d6d964c4a41f01edead05354afbcc5f2cde9f62a372d'}
        transition = contracts.PARSER_AMBIGUITY_SOURCE_TRANSITIONS[parser]
        self.assertEqual(fixed, {key: transition[key] for key in fixed})
        self.assertEqual(6, len(transition['reverse_changes']))
        self.assertEqual(fixed['preimage_sha256'], contracts.DIAGNOSTICS_SOURCE_TRANSITIONS[parser]['successor_sha256'])
        data = contracts.read_source(ROOT, parser)
        prior = contracts.parser_ambiguity_predecessor_bytes(parser, data)
        self.assertEqual((fixed['preimage_bytes'], fixed['preimage_sha256']), (len(prior), contracts.digest(prior)))
        with self.assertRaisesRegex(ValueError, 'Diagnostics SOURCE successor drift'):
            contracts.diagnostics_predecessor_bytes(parser, data)
        historical = contracts.diagnostics_predecessor_bytes(parser, prior)
        self.assertEqual('db0048e7083bfc8d1565d0505d1b3c00ad574e6e50ef2dc39797a947912e3f3e', contracts.digest(historical))
        self.assertEqual(historical, contracts.historical_source_bytes(ROOT, parser))
        for path, pin in EXPECTED_PARSER_AMBIGUITY_SOURCE_PINS.items():
            payload = contracts.read_source(ROOT, path)
            self.assertEqual((pin['bytes'], pin['sha256']), (len(payload), contracts.digest(payload)))
        authored_tests = contracts.read_source(ROOT, source_test).decode()
        self.assertEqual(27, len(kotlin_test_sections(authored_tests)))  # Authored SOURCE, never an execution count.
        with self.assertRaisesRegex(ValueError, 'closed unchanged SOURCE join'):
            contracts.historical_source_bytes(ROOT, source_test)

    def test_parser_ambiguity_inventory_inverse_and_blind_repin_mutations_fail_closed(self):
        parser = MAIN_REL + 'CompilerDiagnosticParser.kt'
        source_test = KOTLIN_REL + 'CompilerHeaderSourceAmbiguityTest.kt'
        with tempfile.TemporaryDirectory(prefix='parser-ambiguity-source-unit-') as directory:
            base = Path(directory)
            for path in EXPECTED_PARSER_AMBIGUITY_SOURCE_PINS:
                target = base / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(contracts.read_source(ROOT, path))
            require_fixed_parser_ambiguity_source_inputs(base)
            for path in EXPECTED_PARSER_AMBIGUITY_SOURCE_PINS:
                target = base / path
                data = target.read_bytes()
                mutations = [data + b'\n// unrelated selected parser source drift\n']
                if path == parser:
                    # These mutations test the fixed SOURCE inventory, not parser execution.
                    overflow = b'line in messageShapedSourceRows || (ambiguityLimitExceeded && isMessageShapedRow(line))'
                    bound = b'MAX_AMBIGUOUS_SOURCE_ROWS = 64'
                    self.assertEqual(1, data.count(overflow))
                    self.assertEqual(1, data.count(bound))
                    mutations.extend([data.replace(overflow, b'line in messageShapedSourceRows', 1),
                                      data.replace(bound, b'MAX_AMBIGUOUS_SOURCE_ROWS = 65', 1)])
                else:
                    self.assertEqual(27, data.count(b'@Test fun'))
                    mutations.append(data.replace(b'@Test fun', b'@Disabled @Test fun', 1))
                for index, mutation in enumerate(mutations):
                    with self.subTest(path=path, mutation=index):
                        self.assertNotEqual(data, mutation)
                        target.write_bytes(mutation)
                        with self.assertRaisesRegex(ValueError, 'Parser ambiguity selected SOURCE drift'):
                            require_fixed_parser_ambiguity_source_inputs(base)
                        repin = {'bytes': len(mutation), 'sha256': contracts.digest(mutation)}
                        with patch.dict(contracts.PARSER_AMBIGUITY_SOURCE_PINS, {path: repin}):
                            with self.assertRaisesRegex(ValueError, 'Parser ambiguity selected SOURCE selector drift'):
                                require_fixed_parser_ambiguity_source_inputs(base)
                    target.write_bytes(data)
                target.unlink()
                with self.assertRaises(ValueError):
                    require_fixed_parser_ambiguity_source_inputs(base)
                target.symlink_to(ROOT / path)
                with self.assertRaisesRegex(ValueError, 'Symlink SOURCE input'):
                    require_fixed_parser_ambiguity_source_inputs(base)
                target.unlink(); target.write_bytes(data)
            renamed = (base / source_test).with_name('UnreviewedParserSourceTest.kt')
            (base / source_test).rename(renamed)
            with self.assertRaises(ValueError):
                require_fixed_parser_ambiguity_source_inputs(base)
            renamed.rename(base / source_test)
            require_fixed_parser_ambiguity_source_inputs(base)
            with patch.dict(contracts.PARSER_AMBIGUITY_SOURCE_PINS, {'unreviewed.kt': {'bytes': 0, 'sha256': '0' * 64}}):
                with self.assertRaisesRegex(ValueError, 'Parser ambiguity selected SOURCE selector drift'):
                    require_fixed_parser_ambiguity_source_inputs(base)
            with patch.dict(contracts.CURRENT_IMPLEMENTATION_SOURCE_PINS, {parser: '0' * 64}):
                with self.assertRaisesRegex(ValueError, 'Parser ambiguity implementation SOURCE selector drift'):
                    require_fixed_parser_ambiguity_source_inputs(base)

            pin = contracts.PARSER_AMBIGUITY_SOURCE_TRANSITIONS[parser]
            data = contracts.read_source(ROOT, parser)
            prior = contracts.parser_ambiguity_predecessor_bytes(parser, data)
            historical = contracts.diagnostics_predecessor_bytes(parser, prior)
            target = base / parser
            self.assertEqual(historical, contracts.historical_source_bytes(base, parser))
            target.write_bytes(prior)
            with self.assertRaisesRegex(ValueError, 'Parser ambiguity SOURCE successor drift'):
                contracts.historical_source_bytes(base, parser)
            target.write_bytes(data)
            mutations = [prior, data + b'\n', data + b'\n// unrelated inverse source drift\n']
            for successor, predecessor in pin['reverse_changes']:
                self.assertTrue(successor)
                self.assertEqual(1, data.count(successor))
                mutations.extend([data.replace(successor, b'', 1), data + successor])
            for index, mutation in enumerate(mutations):
                with self.subTest(mutation=index):
                    self.assertNotEqual(data, mutation)
                    target.write_bytes(mutation)
                    with self.assertRaisesRegex(ValueError, 'Parser ambiguity SOURCE successor drift'):
                        contracts.parser_ambiguity_predecessor_bytes(parser, contracts.read_source(base, parser))
                    with patch.dict(pin, {'successor_bytes': len(mutation), 'successor_sha256': contracts.digest(mutation)}):
                        with self.assertRaises(ValueError):
                            contracts.parser_ambiguity_predecessor_bytes(parser, contracts.read_source(base, parser))
            reverse = pin['reverse_changes']
            changed = ((reverse[0][0], reverse[0][1] + b'\n// changed inverse output\n'),) + reverse[1:]
            with patch.dict(pin, {'reverse_changes': changed}):
                with self.assertRaisesRegex(ValueError, 'Parser ambiguity SOURCE preimage drift'):
                    contracts.parser_ambiguity_predecessor_bytes(parser, data)
            target.unlink()
            with self.assertRaises(ValueError):
                contracts.parser_ambiguity_predecessor_bytes(parser, contracts.read_source(base, parser))
            target.symlink_to(ROOT / parser)
            with self.assertRaisesRegex(ValueError, 'Symlink SOURCE input'):
                contracts.parser_ambiguity_predecessor_bytes(parser, contracts.read_source(base, parser))
            target.unlink(); target.write_bytes(data)
            require_fixed_parser_ambiguity_source_inputs(base)
        for path in ('unreviewed.kt', MAIN_REL + '../compiler/CompilerDiagnosticParser.kt', source_test,
                     MAIN_REL + 'KotlinJvmCompilerProvider.kt', MAIN_REL + 'CompilerCallObservation.kt',
                     'modules/cli/src/main/kotlin/dev/ktchecker/cli/Workbench.kt', FIXTURE_REL + 'generate_fixtures.py'):
            with self.assertRaisesRegex(ValueError, 'closed parser ambiguity SOURCE transition'):
                contracts.parser_ambiguity_predecessor_bytes(path, b'not a reviewed source successor')


if __name__ == '__main__':
    unittest.main()

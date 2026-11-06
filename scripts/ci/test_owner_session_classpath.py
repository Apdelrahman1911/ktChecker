#!/usr/bin/env python3
"""Authored inert structural controls: every ZIP is memory-only and contains fake
class headers/inert native bytes; verify() file/directory observations are mocked.
No filesystem stage, JVM, host, provider, model, package build or process test.
Not executed as part of source-packet authoring. Run only after root grants it.
"""
from __future__ import annotations

from contextlib import contextmanager
import copy
import hashlib
import io
import json
from pathlib import Path
import stat
import struct
import unittest
from unittest import mock
import warnings
import zipfile

import verify_owner_session_classpath as subject

TARGET = 'linux-x86_64'
SOURCE = '/synthetic-source'
HOME = SOURCE + '/build/preview-handoffs/private-preview-synthetic01/install/ktchecker'
INPUT = '/synthetic-structure-selection.json'
HEADER = b'\xca\xfe\xba\xbe\x00\x00\x00\x41'  # NOT executable bytecode.
MAIN = 'dev/ktchecker/cli/MainKt.class'
CATALOG = 'catalog/rules-core.json'


def digest(raw):
    return 'sha256:' + hashlib.sha256(raw).hexdigest()


def entries(target=TARGET):
    result = {name: HEADER if name.endswith('.class') else b'inert-resource-not-semantic-JSON'
        for names in subject.profile(target).values() for name in names}
    for revision, prefix in enumerate(subject.PREFIXES, 1):
        binary = b'inert-native-not-an-executable-' + str(revision).encode('ascii')
        result[prefix + target + '/supervisor'] = binary
        result[prefix + 'manifest'] = ('ktchecker-owned-child-supervisor/preview-' + str(revision) + '\n'
            + target + '\n' + digest(b'no-real-native-source') + '\n' + digest(binary) + '\n').encode('utf-8')
    return result


def archive(items):
    if isinstance(items, dict):
        items = items.items()
    buffer = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)  # Intentional duplicate ZIP-entry fixture.
        with zipfile.ZipFile(buffer, 'w', compression=zipfile.ZIP_STORED) as output:
            for name, payload in items:
                output.writestr(name, payload)
    return buffer.getvalue()


def selection(raws, target=TARGET):
    return {'schema': subject.SELECTION_SCHEMA, 'sourceRoot': SOURCE,
        'distributionHome': HOME, 'nativeTarget': target,
        'jvmClasspath': [{'label': f'selected/{index:02d}', 'path': HOME + f'/lib/explicit-{index:02d}.jar',
            'digest': digest(raw), 'bytes': len(raw)} for index, raw in enumerate(raws)]}


def joined(raws, target=TARGET):
    return subject.join_structure([subject.inspect_jar(raw, target) for raw in raws], target)


def observation_rows(raws, choice):
    encoded = json.dumps(choice, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    selected = (encoded, (7, 9001, stat.S_IFREG | 0o400, 1, len(encoded), 10, 20))
    rows = [(raw, (7, index + 100, stat.S_IFREG | 0o400, 1, len(raw), 10, 20))
        for index, raw in enumerate(raws)]
    return encoded, [selected, *rows, *rows, selected]


@contextmanager
def fake_observation(readings):
    # All IO seams are replaced, including directory stats. This exercises only
    # the new verifier's pin/order/recheck logic, never its reused no-follow reader.
    directories = {Path(HOME): (7, 80, stat.S_IFDIR | 0o500, 2, 0, 10, 20),
        Path(HOME) / 'lib': (7, 81, stat.S_IFDIR | 0o500, 2, 0, 10, 20)}
    def checked(path, *, directory=False):
        assert directory
        return path, directories[path]
    with mock.patch.object(subject.sys, 'platform', 'linux'), \
         mock.patch.object(subject.time, 'monotonic', return_value=1), \
         mock.patch.object(subject, 'read_file', side_effect=readings) as reader, \
         mock.patch.object(subject, 'checked_path', side_effect=checked), \
         mock.patch.object(subject, 'fingerprint', side_effect=lambda row: row):
        yield reader


class OwnerSessionClasspathStructureTest(unittest.TestCase):
    def rejected(self, code, operation):
        with self.assertRaisesRegex(subject.StructureError, '^' + code + '$'):
            operation()

    def test_explicit_group_order_and_co_location_without_artifact_names(self):
        wanted = entries()
        order = list(reversed(subject.profile(TARGET)))
        raws = [archive({name: wanted[name] for name in subject.profile(TARGET)[group]}) for group in order]
        selected = selection(raws)
        self.assertIs(subject.validate_selection(selected), selected)
        result = joined(raws)
        self.assertEqual(result['groups'], {group: index for index, group in enumerate(order)})
        self.assertEqual({row['entry'] for row in result['entries']}, set(wanted))
        # A fat JAR is permitted, but this does NOT prove arbitrary transitives.
        self.assertEqual(set(joined([archive(wanted)])['groups'].values()), {0})

    def test_inert_aarch64_target_is_explicit_not_host_detection(self):
        result = joined([archive(entries('linux-aarch64'))], 'linux-aarch64')
        self.assertEqual([row['target'] for row in result['native_resource_consistency']], ['linux-aarch64'] * 2)
        self.rejected('explicit_linux_resource_target_required', lambda: subject.profile('windows-x86_64'))

    def test_exact_order_rechecks_and_narrow_report_flags_with_mocked_io(self):
        wanted = entries()
        order = list(reversed(subject.profile(TARGET)))
        raws = [archive({name: wanted[name] for name in subject.profile(TARGET)[group]}) for group in order]
        choice = selection(raws)
        encoded, readings = observation_rows(raws, choice)
        with fake_observation(readings) as reader:
            report = subject.verify(INPUT, digest(encoded))
        self.assertEqual(report['state'], 'verified_structure_only')
        self.assertEqual(report['profile'], subject.PROFILE)
        self.assertEqual(report['jvm_classpath'], choice['jvmClasspath'])
        self.assertEqual(report['jvm_classpath_digest'], digest(json.dumps(choice['jvmClasspath'],
            ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')))
        self.assertEqual(report['input_recheck'], 'unchanged')
        self.assertEqual(report['groups'], {group: index for index, group in enumerate(order)})
        self.assertEqual(report['selected_file_identities'], [[7, index + 100] for index in range(len(raws))])
        for flag in ('class_file_verification', 'resource_semantics_verified', 'native_abi_qualified',
                'complete_transitive_closure', 'build_provenance_verified', 'jvm_executed', 'owner_opened',
                'host_executed', 'worker_executed', 'release_qualified'):
            self.assertIs(report[flag], False)
        paths = [call.args[0] for call in reader.call_args_list]
        vector = [Path(pin['path']) for pin in choice['jvmClasspath']]
        self.assertEqual(paths, [Path(INPUT), *vector, *vector, Path(INPUT)])

    def test_every_profile_leaf_is_required_but_no_transitive_claim_is_made(self):
        complete = entries()
        for absent in complete:
            with self.subTest(absent=absent):
                changed = dict(complete); del changed[absent]
                self.rejected('required_entry_missing', lambda: joined([archive(changed)]))

    def test_one_anchor_resource_split_across_selected_jars_is_not_co_location(self):
        complete = entries(); moved = complete.pop(CATALOG)
        self.rejected('required_group_split_across_jars', lambda: joined([archive(complete), archive({CATALOG: moved})]))

    def test_required_shadow_and_duplicate_entry_fail(self):
        complete = entries()
        self.rejected('shadowed_required_entry', lambda: joined([archive(complete), archive({MAIN: HEADER})]))
        duplicate = [*complete.items(), (MAIN, HEADER)]
        self.rejected('ambiguous_jar_entry', lambda: subject.inspect_jar(archive(duplicate), TARGET))

    def test_required_multi_release_class_and_resource_overrides_fail(self):
        for name in (MAIN, CATALOG):
            with self.subTest(name=name):
                complete = entries(); complete['META-INF/versions/21/' + name] = complete[name]
                self.rejected('versioned_required_entry', lambda: subject.inspect_jar(archive(complete), TARGET))

    def test_required_directory_and_symlink_entries_fail(self):
        self.rejected('required_entry_not_file', lambda: subject.inspect_jar(archive({MAIN + '/': b'x'}), TARGET))
        info = zipfile.ZipInfo(MAIN); info.create_system = 3; info.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.rejected('required_entry_not_regular', lambda: subject.inspect_jar(archive([(info, HEADER)]), TARGET))

    def test_manifest_classpath_continuations_case_and_unicode_are_not_hidden_expansion(self):
        for raw in (b'Manifest-Version: 1.0\r\nClass-Path: extra.jar\r\n\r\n',
                b'Manifest-Version: 1.0\nClAsS-PaTh: \n extra.jar\n\n',
                'Manifest-Version: 1.0\nClass-Path: \u00a0\n\n'.encode('utf-8')):
            with self.subTest(raw=raw):
                self.rejected('unselected_manifest_classpath', lambda: subject.inspect_jar(
                    archive({'META-INF/MANIFEST.MF': raw}), TARGET))
        subject.main_manifest(b'Manifest-Version: 1.0\nClass-Path:  \t\n\n')
        subject.main_manifest(b'Manifest-Version: 1.0\n\nName: ignored.class\nClass-Path: not-main-section\n\n')
        self.rejected('jar_manifest_duplicate_attribute', lambda: subject.main_manifest(
            b'Manifest-Version: 1.0\nClass-Path: \nclass-path: \n\n'))

    def test_manifest_case_variants_and_jar_index_are_not_ignored(self):
        for name in ('META-INF/manifest.mf', 'meta-inf/MANIFEST.MF', 'META-INF/MANIFEST.MF/'):
            with self.subTest(name=name):
                self.rejected('ambiguous_jar_manifest', lambda: subject.inspect_jar(archive({name: b'x'}), TARGET))
        for name in ('META-INF/INDEX.LIST', 'meta-inf/index.list', 'META-INF/INDEX.LIST/'):
            with self.subTest(name=name):
                self.rejected('unselected_jar_index', lambda: subject.inspect_jar(archive({name: b'x'}), TARGET))

    def test_class_headers_only_reject_bad_future_and_preview_values(self):
        for raw in (b'notclass', HEADER[:7]):
            with self.subTest(raw=raw):
                self.rejected('required_class_header', lambda: subject.inspect_jar(archive({MAIN: raw}), TARGET))
        for minor, major in ((0, 66), (65535, 65), (0, 44), (1, 65)):
            with self.subTest(minor=minor, major=major):
                raw = b'\xca\xfe\xba\xbe' + struct.pack('>HH', minor, major)
                self.rejected('required_class_not_java21_nonpreview', lambda: subject.inspect_jar(archive({MAIN: raw}), TARGET))

    def test_native_manifest_unavailable_version_target_and_digest_shape_fail(self):
        for prefix in subject.PREFIXES:
            original = entries()[prefix + 'manifest'].decode('utf-8').split('\n')
            for line, replacement in ((0, 'ktchecker-owned-child-supervisor/preview-99'),
                    (1, 'linux-aarch64'), (2, 'source-not-pinned'), (3, 'unavailable')):
                with self.subTest(prefix=prefix, line=line):
                    complete = entries(); changed = list(original); changed[line] = replacement
                    complete[prefix + 'manifest'] = '\n'.join(changed).encode('utf-8')
                    self.rejected('native_manifest_invalid_or_unavailable', lambda: joined([archive(complete)]))

    def test_native_bytes_must_match_claim_but_no_abi_or_provenance_is_inferred(self):
        for prefix in subject.PREFIXES:
            with self.subTest(prefix=prefix):
                complete = entries(); complete[prefix + TARGET + '/supervisor'] += b'changed'
                self.rejected('native_resource_digest_mismatch', lambda: joined([archive(complete)]))
        consistent = joined([archive(entries())])['native_resource_consistency']
        self.assertEqual([row['source_digest_claim'] for row in consistent], [digest(b'no-real-native-source')] * 2)

    def test_selection_strict_keys_bytes_digest_unique_pins_and_direct_lib(self):
        original = selection([archive(entries())])
        cases = [({'extra': True}, None, 'selection_schema'),
            ({'nativeTarget': 'macos-aarch64'}, None, 'explicit_linux_resource_target_required'),
            ({'jvmClasspath': []}, None, 'classpath_count'),
            ({}, {'bytes': True}, 'classpath_pin_digest_or_size'),
            ({}, {'bytes': 0}, 'classpath_pin_digest_or_size'),
            ({}, {'digest': 'sha256:' + 'A' * 64}, 'classpath_pin_digest_or_size'),
            ({}, {'extra': True}, 'classpath_pin_keys'),
            ({}, {'path': HOME + '/providers/compiler/extra.jar'}, 'selected_lib_jar_required'),
            ({}, {'path': HOME + '/lib/*.jar'}, 'selected_lib_jar_required'),
            ({}, {'path': HOME + '/lib/../other.jar'}, 'noncanonical_path'),
            ({}, {'label': '../escape'}, 'classpath_pin_label')]
        for root_changes, pin_changes, code in cases:
            with self.subTest(code=code, root_changes=root_changes, pin_changes=pin_changes):
                changed = copy.deepcopy(original); changed.update(root_changes)
                if pin_changes is not None: changed['jvmClasspath'][0].update(pin_changes)
                self.rejected(code, lambda: subject.validate_selection(changed))
        changed = copy.deepcopy(original); changed['jvmClasspath'] *= 2
        self.rejected('duplicate_classpath_pin', lambda: subject.validate_selection(changed))

    def test_selection_new_stage_shape_is_not_a_freshness_proof(self):
        for home in (SOURCE + '/modules/cli/build/install/ktchecker',
                SOURCE + '/build/preview-handoffs/ktchecker-old/install/ktchecker',
                SOURCE + '/build/preview-handoffs/private-preview-short/install/ktchecker'):
            with self.subTest(home=home):
                changed = selection([archive(entries())]); changed['distributionHome'] = home
                self.rejected('new_private_stage_required', lambda: subject.validate_selection(changed))

    def test_unicode_quote_dollar_bracket_basename_is_data_on_linux_not_windows_qualification(self):
        changed = selection([archive(entries())])
        changed['jvmClasspath'][0]['path'] = HOME + '/lib/Unicode-\u03bb-quote\'-double"-$[x].jar'
        before = copy.deepcopy(changed)
        self.assertEqual(subject.validate_selection(changed), before)
        for path in ('relative.json', '/root//selection.json', '/root/./selection.json', '/root/a:bad.json', '/root/a\\bad.json'):
            with self.subTest(path=path):
                with self.assertRaises(subject.StructureError): subject.absolute(path)

    def test_selection_json_duplicate_keys_nonfinite_and_malformed_fail(self):
        self.rejected('duplicate_selection_key', lambda: subject.decode(b'{"schema":1,"schema":2}'))
        self.rejected('nonfinite_selection_number', lambda: subject.decode(b'{"bytes":NaN}'))
        self.rejected('invalid_selection_json', lambda: subject.decode(b'{broken'))

    def test_jar_allocation_prebound_checks_actual_central_record_count(self):
        raw = bytearray(archive({MAIN: HEADER, 'some-resource': b'x'})); end = len(raw) - 22
        struct.pack_into('<HH', raw, end + 8, 1, 1)  # False small count, two actual central entries.
        with mock.patch.object(subject, 'open_zip') as opener:
            self.rejected('jar_central_records', lambda: subject.inspect_jar(bytes(raw), TARGET))
            opener.assert_not_called()
        struct.pack_into('<HH', raw, end + 8, subject.MAX_ENTRIES + 1, subject.MAX_ENTRIES + 1)
        with mock.patch.object(subject, 'open_zip') as opener:
            self.rejected('jar_entry_limit_or_layout', lambda: subject.inspect_jar(bytes(raw), TARGET))
            opener.assert_not_called()

    def test_aggregate_required_payload_and_inventory_bounds(self):
        inspected = subject.inspect_jar(archive(entries()), TARGET)
        with mock.patch.object(subject, 'MAX_SELECTED_ENTRIES_BYTES', inspected['selected_bytes'] - 1):
            self.rejected('selected_entry_total_limit', lambda: subject.join_structure([inspected], TARGET))
        with mock.patch.object(subject, 'MAX_TOTAL_ENTRIES', inspected['entry_count'] - 1):
            self.rejected('classpath_total_entry_limit', lambda: subject.join_structure([inspected], TARGET))

    def test_initial_selection_digest_and_jar_pin_changes_fail(self):
        raws = [archive(entries())]; choice = selection(raws)
        encoded, readings = observation_rows(raws, choice)
        with fake_observation(readings) as reader:
            self.rejected('selection_digest_mismatch', lambda: subject.verify(INPUT, 'sha256:' + '0' * 64))
            self.assertEqual(reader.call_count, 1)
        readings[1] = (raws[0] + b'changed', readings[1][1])
        with fake_observation(readings):
            self.rejected('selected_jar_pin_mismatch', lambda: subject.verify(INPUT, digest(encoded)))

    def test_selected_recheck_and_selection_recheck_drift_fail(self):
        raws = [archive(entries())]; choice = selection(raws)
        encoded, original = observation_rows(raws, choice)
        for index, code in ((2, 'selected_jar_changed'), (3, 'selection_changed')):
            with self.subTest(index=index):
                readings = list(original); raw, observed = readings[index]
                readings[index] = (raw, (*observed[:-1], observed[-1] + 1))
                with fake_observation(readings):
                    self.rejected(code, lambda: subject.verify(INPUT, digest(encoded)))

    def test_non_linux_never_enters_reused_native_file_reader(self):
        with mock.patch.object(subject.sys, 'platform', 'win32'), mock.patch.object(subject, 'read_file') as reader:
            self.rejected('linux_structural_profile_only', lambda: subject.verify(INPUT, 'sha256:' + '0' * 64))
            reader.assert_not_called()

    def test_cli_failure_does_not_print_arbitrary_input_error(self):
        output = io.StringIO()
        with mock.patch.object(subject, 'verify', side_effect=OSError('PRIVATE-UNTRUSTED-DETAIL')), \
             mock.patch.object(subject.sys, 'stdout', output):
            result = subject.main(['--selection', INPUT, '--selection-digest', 'sha256:' + '0' * 64])
        self.assertEqual(result, 1)
        self.assertNotIn('PRIVATE-UNTRUSTED-DETAIL', output.getvalue())
        self.assertEqual(json.loads(output.getvalue())['errors'], ['input_unreadable_or_malformed'])

    def test_stdout_delivery_failure_cannot_return_success(self):
        output = mock.Mock(); output.flush.side_effect = OSError('synthetic-undelivered')
        with mock.patch.object(subject, 'verify', return_value={'state': 'verified_structure_only'}), \
             mock.patch.object(subject.sys, 'stdout', output):
            with self.assertRaises(OSError):
                subject.main(['--selection', INPUT, '--selection-digest', 'sha256:' + '0' * 64])


if __name__ == '__main__':
    unittest.main()

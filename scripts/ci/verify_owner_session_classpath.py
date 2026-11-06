#!/usr/bin/env python3
"""Read only an explicit, pinned new-stage JAR order; never load/execute its code.

Checks a fixed minimum owner-session/Workbench entry profile, not full transitive
closure, bytecode/linkage validity, resource semantics, build provenance, native
ABI, installed-owner readiness or loaded-memory attestation. No build, discovery,
extraction, output file, process, host/provider/model, retry or cleanup operation.
Run with -B in a trusted source/interpreter context. Stdout is DATA; root must bind
actual settled exit0 and its complete captured bytes. A separate reviewed stage
build and matching TS28 controller/image/transport selections remain prerequisites.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import stat
import struct
import sys
import time
import zipfile
import zlib

# Reuse the existing separately reviewed bounded/no-follow regular-file and ZIP32
# readers. No private helper is modified; its native Windows branch is NOT used.
from verify_model_worker_distribution import (WorkerVerificationError, checked_path,
    fingerprint, open_zip, physical_identity, read_file)

MIB = 1024 * 1024
MAX_JAR = 128 * MIB
MAX_TOTAL = 512 * MIB
MAX_ENTRIES = 50_000
MAX_TOTAL_ENTRIES = 250_000
MAX_ENTRY = 8 * MIB  # The existing core/platform catalog files exceed4MiB.
MAX_SELECTED_ENTRIES_BYTES = 32 * MIB
SELECTION_SCHEMA = 'ktchecker-owner-session-classpath-structure-selection/1'
REPORT_SCHEMA = 'ktchecker-owner-session-classpath-structure/1'
PROFILE = 'owner-session-r1-direct-entry-minimum-and-workbench-roots/1'
SHA = re.compile(r'sha256:[0-9a-f]{64}\Z')
TARGETS = ('linux-x86_64', 'linux-aarch64')
PREFIXES = ('dev/ktchecker/providers/runtime/native/', 'dev/ktchecker/providers/runtime/native-live/')

# Groups require co-location in one selected JAR. Different groups need not be in
# different JARs; this is not an artifact-name guess or a fat-JAR prohibition.
GROUPS = {
    'cli': (
        *(f'dev/ktchecker/cli/{name}.class' for name in (
            'MainKt', 'Workbench', 'Catalog', 'OwnerModelCommandSession',
            'OwnerModelSessionTransportKt', 'OwnerModelSessionTransport',
            'OwnerModelTransportCliSelection', 'OwnerModelTransportFrames',
            'OwnerModelTransportState', 'OwnerModelTransportRequest',
            'OwnerModelTransportPhase', 'OwnerModelTransportClockKind',
            'OwnerModelTransportPreparedSelection', 'OwnerLiveModelRuntime')),
        *(f'catalog/rules-{name}.json' for name in ('core', 'libraries', 'platform', 'ecosystem')),
        'ktchecker-owner-model-origin.init.gradle',
    ),
    'contracts': ('dev/ktchecker/contracts/AnalysisInput.class',),
    'snapshot': ('dev/ktchecker/snapshot/SnapshotStore.class',),
    'kernel': ('dev/ktchecker/kernel/KernelService.class',),
    'config': ('dev/ktchecker/config/ConfigStore.class',),
    'project-model': ('dev/ktchecker/projectmodel/ProjectModelImporter.class',
        'dev/ktchecker/projectmodel/GradleModelExporter.class', 'ktchecker-model.init.gradle'),
    'review-controller': ('dev/ktchecker/reviewcontroller/OwnerPlanAssembler.class',),
    'worker-protocol': ('dev/ktchecker/workerprotocol/ApplicationWire.class',),
    'model-wire': ('dev/ktchecker/workerprotocol/CompilerModelWire.class',),
    'provider-runtime': ('dev/ktchecker/providers/runtime/IsolatedProcessRunner.class',
        'dev/ktchecker/providers/runtime/OwnedApplicationDeadline.class',
        'dev/ktchecker/providers/runtime/OwnedProcessSupervisor.class',
        'dev/ktchecker/providers/runtime/SupervisorImageLoader.class',
        'dev/ktchecker/providers/runtime/OwnedLiveSupervisorImageLoader.class'),
    'provider-compiler': ('dev/ktchecker/compiler/KotlinJvmCompilerProvider.class',
        'dev/ktchecker/compiler/CompilerRuleRegistry.class',
        'dev/ktchecker/compiler/live/model/KotlinJvmModelLiveCompilerProvider.class', 'compiler-rule-map.json'),
    'provider-detekt': ('dev/ktchecker/providers/detekt/DetektProvider.class',
        'dev/ktchecker/providers/detekt/DetektCatalog.class', 'detekt-rule-map.json', 'detekt-artifacts.json'),
    'provider-ktlint': ('dev/ktchecker/providers/ktlint/KtlintProvider.class',
        'dev/ktchecker/providers/ktlint/KtlintCatalog.class', 'ktlint-rule-map.json', 'ktlint-artifacts.json'),
    'provider-lint': ('dev/ktchecker/providers/lint/AndroidLintProvider.class',
        'dev/ktchecker/providers/lint/LintCatalog.class', 'dev/ktchecker/providers/lint/android-lint-catalog.json'),
    'provider-native': ('dev/ktchecker/providers/native/KotlinNativeProvider.class',
        'dev/ktchecker/providers/native/NativeCatalog.class', 'dev/ktchecker/providers/native/kotlin-native-catalog.json'),
    'kotlin-stdlib': ('kotlin/Unit.class',),
    'serialization-core': ('kotlinx/serialization/KSerializer.class',),
    'serialization-json': ('kotlinx/serialization/json/Json.class',),
    'sqlite-jdbc': ('org/sqlite/JDBC.class',),
}


class StructureError(ValueError):
    """Fixed diagnostics only; no archive content, untrusted path or exception text."""


def need(condition, code):
    if not condition:
        raise StructureError(code)


def sha(raw):
    return 'sha256:' + hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')


def unique(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result, 'duplicate_selection_key')
        result[key] = value
    return result


def decode(raw):
    need(type(raw) is bytes and 0 < len(raw) <= MIB, 'selection_byte_limit')
    try:
        return json.loads(raw.decode('utf-8'), object_pairs_hook=unique,
            parse_constant=lambda _: (_ for _ in ()).throw(StructureError('nonfinite_selection_number')))
    except (UnicodeError, ValueError, RecursionError) as error:
        if isinstance(error, StructureError):
            raise
        raise StructureError('invalid_selection_json') from None


def absolute(value):
    need(type(value) is str and 0 < len(value.encode('utf-8')) <= 8192 and value.startswith('/')
        and not re.search(r'[\x00-\x1f\x7f\\:]', value), 'absolute_path_required')
    need(all(part not in ('', '.', '..') for part in value.split('/')[1:]), 'noncanonical_path')
    return Path(value)


def validate_selection(value):
    need(type(value) is dict and set(value) == {'schema', 'sourceRoot', 'distributionHome', 'nativeTarget', 'jvmClasspath'}
        and value['schema'] == SELECTION_SCHEMA, 'selection_schema')
    source = absolute(value['sourceRoot'])
    home = absolute(value['distributionHome'])
    try:
        stage = home.relative_to(source / 'build/preview-handoffs').parts
    except ValueError:
        raise StructureError('new_private_stage_required') from None
    need(len(stage) == 3 and re.fullmatch(r'private-preview-[A-Za-z0-9_-]{8,96}', stage[0])
        and stage[1:] == ('install', 'ktchecker'), 'new_private_stage_required')
    need(value['nativeTarget'] in TARGETS, 'explicit_linux_resource_target_required')
    pins = value['jvmClasspath']
    need(type(pins) is list and 1 <= len(pins) <= 128, 'classpath_count')
    paths, labels, total = set(), set(), 0
    for pin in pins:
        need(type(pin) is dict and set(pin) == {'label', 'path', 'digest', 'bytes'}, 'classpath_pin_keys')
        label = pin['label']
        need(type(label) is str and 0 < len(label.encode('utf-8')) <= 4096 and not label.startswith('/')
            and not re.search(r'[\x00-\x1f\x7f\\]', label)
            and all(x not in ('', '.', '..') for x in label.split('/')), 'classpath_pin_label')
        path = absolute(pin['path'])
        need(path.parent == home / 'lib' and path.suffix == '.jar' and '*' not in str(path), 'selected_lib_jar_required')
        need(type(pin['digest']) is str and SHA.fullmatch(pin['digest']) and type(pin['bytes']) is int
            and 0 < pin['bytes'] <= MAX_JAR, 'classpath_pin_digest_or_size')
        need(pin['path'] not in paths and label not in labels, 'duplicate_classpath_pin')
        paths.add(pin['path']); labels.add(label); total += pin['bytes']
        need(total <= MAX_TOTAL, 'classpath_total_byte_limit')
    return value


def profile(target):
    need(target in TARGETS, 'explicit_linux_resource_target_required')
    groups = dict(GROUPS)
    groups['provider-runtime'] += tuple(name for prefix in PREFIXES for name in (prefix + 'manifest', prefix + target + '/supervisor'))
    return groups


def main_manifest(raw):
    """Reject JAR Class-Path expansion; never silently discover its extra URLs/JARs."""
    need(len(raw) <= 64 * 1024, 'jar_manifest_limit')
    try:
        lines = raw.decode('utf-8').replace('\r\n', '\n').split('\n')
    except UnicodeError:
        raise StructureError('jar_manifest_invalid') from None
    fields = {}
    previous = None
    for line in lines:
        if not line:
            break  # Only the main manifest section controls Class-Path.
        need('\r' not in line and '\x00' not in line, 'jar_manifest_invalid')
        if line.startswith(' '):
            need(previous is not None, 'jar_manifest_invalid')
            fields[previous] += line[1:]
        else:
            key, separator, value = line.partition(': ')
            need(separator and re.fullmatch(r'[A-Za-z0-9_-]+', key), 'jar_manifest_invalid')
            key = key.lower()
            need(key not in fields, 'jar_manifest_duplicate_attribute')
            fields[key] = value; previous = key
    need(not fields.get('class-path', '').strip(' \t\r\n\f'), 'unselected_manifest_classpath')


def bounded_central_count(raw):
    # Count the actual bounded central records before ZipInfo allocation. A forged
    # small EOCD count must not cause the standard reader to allocate a huge list.
    end = raw.rfind(b'PK\x05\x06', max(0, len(raw) - 65557))
    need(end >= 0 and end + 22 <= len(raw), 'jar_end_record')
    _, disk, central_disk, disk_count, count, size, start, comment = struct.unpack_from('<4s4H2IH', raw, end)
    need(disk == central_disk == 0 and disk_count == count and 0 < count <= MAX_ENTRIES
        and size <= 16 * MIB and start + size == end and end + 22 + comment == len(raw), 'jar_entry_limit_or_layout')
    position = start
    for _ in range(count):
        need(position + 46 <= end and raw[position:position + 4] == b'PK\x01\x02', 'jar_central_records')
        position += 46 + sum(struct.unpack_from('<3H', raw, position + 28))
        need(position <= end, 'jar_central_records')
    need(position == end, 'jar_central_records')
    return count


def inspect_jar(raw, target):
    """Bounded in-memory ZIP inspection only; returned entries cannot mint authority."""
    need(type(raw) is bytes and 0 < len(raw) <= MAX_JAR, 'jar_byte_limit')
    groups = profile(target)
    wanted = {name for names in groups.values() for name in names}
    count = bounded_central_count(raw)
    entries, manifests, seen, uncompressed = {}, {}, set(), 0
    with open_zip(raw, worker=False) as archive:
        for item in archive.infolist():
            name = item.orig_filename
            need(name not in seen and '\x00' not in name and '\\' not in name, 'ambiguous_jar_entry')
            seen.add(name)
            # Some JAR readers find metadata case-insensitively. Never skip a
            # hidden Class-Path manifest or a JAR index capable of extra lookup.
            need(name.rstrip('/').upper() != 'META-INF/MANIFEST.MF' or name == 'META-INF/MANIFEST.MF', 'ambiguous_jar_manifest')
            need(name.rstrip('/').upper() != 'META-INF/INDEX.LIST', 'unselected_jar_index')
            versioned = re.fullmatch(r'META-INF/versions/[0-9]+/(.+)', name)
            need(versioned is None or versioned[1] not in wanted, 'versioned_required_entry')
            need(not (name.endswith('/') and name[:-1] in wanted), 'required_entry_not_file')
            if name not in wanted and name != 'META-INF/MANIFEST.MF':
                continue
            mode = item.external_attr >> 16
            need(not item.is_dir() and stat.S_IFMT(mode) in (0, stat.S_IFREG)
                and not item.flag_bits & (1 | 64) and item.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), 'required_entry_not_regular')
            limit = 64 * 1024 if name == 'META-INF/MANIFEST.MF' else 512 if name in {p + 'manifest' for p in PREFIXES} else MIB if name.endswith('/supervisor') else MAX_ENTRY
            need(0 < item.file_size <= limit and 0 <= item.compress_size <= MAX_ENTRY, 'required_entry_size')
            with archive.open(item) as handle:
                payload = handle.read(limit + 1)
            need(len(payload) == item.file_size and len(payload) <= limit, 'required_entry_readback')
            if name == 'META-INF/MANIFEST.MF':
                main_manifest(payload)
                continue
            uncompressed += len(payload)
            need(uncompressed <= MAX_SELECTED_ENTRIES_BYTES, 'selected_entry_total_limit')
            if name.endswith('.class'):
                need(len(payload) >= 8 and payload[:4] == b'\xca\xfe\xba\xbe', 'required_class_header')
                minor, major = struct.unpack_from('>HH', payload, 4)
                need(minor == 0 and 45 <= major <= 65, 'required_class_not_java21_nonpreview')
            entries[name] = {'entry': name, 'bytes': len(payload), 'digest': sha(payload)}
            if name in {p + 'manifest' for p in PREFIXES}:
                manifests[name] = payload
    need(len(seen) == count, 'jar_central_records')
    return {'entries': entries, 'native_manifests': manifests, 'entry_count': len(seen), 'selected_bytes': uncompressed}


def join_structure(inspections, target):
    groups, entries, manifests = profile(target), {}, {}
    need(sum(x['entry_count'] for x in inspections) <= MAX_TOTAL_ENTRIES, 'classpath_total_entry_limit')
    need(sum(x['selected_bytes'] for x in inspections) <= MAX_SELECTED_ENTRIES_BYTES, 'selected_entry_total_limit')
    for index, inspected in enumerate(inspections):
        for name, row in inspected['entries'].items():
            need(name not in entries, 'shadowed_required_entry')
            entries[name] = {**row, 'classpath_index': index}
        manifests.update(inspected['native_manifests'])
    owners = {}
    for group, names in groups.items():
        need(all(name in entries for name in names), 'required_entry_missing')
        indices = {entries[name]['classpath_index'] for name in names}
        need(len(indices) == 1, 'required_group_split_across_jars')
        owners[group] = next(iter(indices))
    native = []
    for revision, prefix in enumerate(PREFIXES, 1):
        try:
            lines = manifests[prefix + 'manifest'].decode('utf-8').split('\n')
        except UnicodeError:
            raise StructureError('native_manifest_invalid') from None
        need(len(lines) == 5 and lines[-1] == '' and lines[0] == f'ktchecker-owned-child-supervisor/preview-{revision}'
            and lines[1] == target and SHA.fullmatch(lines[2]) and SHA.fullmatch(lines[3]), 'native_manifest_invalid_or_unavailable')
        need(entries[prefix + target + '/supervisor']['digest'] == lines[3], 'native_resource_digest_mismatch')
        native.append({'revision': revision, 'target': target, 'source_digest_claim': lines[2], 'binary_digest': lines[3]})
    return {'groups': owners, 'entries': [entries[name] for name in sorted(entries)], 'native_resource_consistency': native}


def verify(selection_path, selection_digest):
    selection_path = absolute(selection_path)
    need(sys.platform == 'linux', 'linux_structural_profile_only')
    need(type(selection_digest) is str and SHA.fullmatch(selection_digest), 'selection_digest_required')
    started = time.monotonic()
    def timely():
        need(time.monotonic() - started <= 120, 'structural_observation_deadline')
    raw, selection_stat = read_file(selection_path, MIB)
    need(sha(raw) == selection_digest, 'selection_digest_mismatch')
    choice = validate_selection(decode(raw)); timely()
    home = Path(choice['distributionHome'])
    directories = {str(path): fingerprint(checked_path(path, directory=True)[1]) for path in (home, home / 'lib')}
    inspections, observed, identities = [], [], set()
    for pin in choice['jvmClasspath']:
        timely()
        payload, observed_stat = read_file(Path(pin['path']), MAX_JAR)
        need(len(payload) == pin['bytes'] and sha(payload) == pin['digest'], 'selected_jar_pin_mismatch')
        identity = physical_identity(observed_stat)
        need(identity not in identities, 'selected_jar_physical_alias')
        identities.add(identity)
        inspections.append(inspect_jar(payload, choice['nativeTarget'])); del payload
        observed.append(observed_stat)
        need(sum(x['entry_count'] for x in inspections) <= MAX_TOTAL_ENTRIES, 'classpath_total_entry_limit')
        timely()
    joined = join_structure(inspections, choice['nativeTarget'])
    # Re-read only the explicit selected vector, never discover/sort installation JARs.
    for pin, previous in zip(choice['jvmClasspath'], observed):
        timely()
        payload, current = read_file(Path(pin['path']), MAX_JAR)
        need(current == previous and len(payload) == pin['bytes'] and sha(payload) == pin['digest'], 'selected_jar_changed')
        del payload
    need(all(fingerprint(checked_path(Path(path), directory=True)[1]) == before for path, before in directories.items()), 'selected_directory_changed')
    final, current = read_file(selection_path, MIB)
    need(current == selection_stat and final == raw, 'selection_changed'); timely()
    return {'schema': REPORT_SCHEMA, 'profile': PROFILE, 'state': 'verified_structure_only', 'selection_digest': selection_digest,
        'distribution_home': choice['distributionHome'], 'jvm_classpath': choice['jvmClasspath'],
        'jvm_classpath_digest': sha(canonical(choice['jvmClasspath'])), 'input_recheck': 'unchanged',
        'selected_file_identities': [list(physical_identity(row)) for row in observed], **joined,
        'required_class_header_feature_limit': 21, 'class_file_verification': False, 'resource_semantics_verified': False,
        'native_abi_qualified': False, 'complete_transitive_closure': False, 'build_provenance_verified': False,
        'jvm_executed': False, 'owner_opened': False, 'host_executed': False, 'worker_executed': False,
        'release_qualified': False, 'publication': 'not_performed', 'license_selection': 'not_performed', 'errors': []}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--selection', required=True)
    parser.add_argument('--selection-digest', required=True)
    args = parser.parse_args(argv)
    try:
        result = verify(args.selection, args.selection_digest)
    except (StructureError, WorkerVerificationError) as error:
        result = {'schema': REPORT_SCHEMA, 'state': 'failed', 'errors': [str(error)], 'jvm_executed': False,
            'owner_opened': False, 'host_executed': False, 'worker_executed': False, 'release_qualified': False}
    except (OSError, ValueError, TypeError, KeyError, RecursionError, RuntimeError, EOFError, struct.error, zipfile.BadZipFile, zlib.error):
        result = {'schema': REPORT_SCHEMA, 'state': 'failed', 'errors': ['input_unreadable_or_malformed'],
            'jvm_executed': False, 'owner_opened': False, 'host_executed': False, 'worker_executed': False, 'release_qualified': False}
    sys.stdout.write(json.dumps(result, ensure_ascii=False, sort_keys=True) + '\n')
    sys.stdout.flush()  # Delivery failure must not produce an exit0 receipt.
    return 0 if result['state'] == 'verified_structure_only' else 1


if __name__ == '__main__':
    raise SystemExit(main())

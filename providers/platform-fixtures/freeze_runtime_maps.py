#!/usr/bin/env python3
"""Freeze minimal runtime mappings from the audited planning inventory, never qualify rules."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
rows = json.loads((ROOT / 'data/rules-platform.json').read_text())
imports = [r for r in rows if r.get('upstream_registry') and r.get('upstream_id')]
assert len(imports) == 609 and len({r['id'] for r in imports}) == 609
catalog = []
for r in imports:
    builtin = r['upstream_registry'] == 'com.android.tools.lint.checks.BuiltinIssueRegistry'
    versions = []
    for lint_version, availability in [('32.3.1', 'upstream_baseline_available'), ('32.5.0', 'upstream_newer_available')]:
        if not r[availability]:
            continue
        coordinate = f'com.android.tools.lint:lint-checks:{lint_version}' if builtin else r['provider']
        baseline = builtin and lint_version == '32.3.1'
        versions.append(dict(
            lintVersion=lint_version, coordinate=coordinate,
            binaryDigest='sha256:' + r.get('upstream_baseline_provider_binary_sha256', r['upstream_provider_binary_sha256']) if baseline else 'sha256:' + r['upstream_provider_binary_sha256'],
            scope=r.get('upstream_baseline_scope', r['upstream_scope']) if baseline else r['upstream_scope'],
            severity=r.get('upstream_baseline_severity', r['upstream_severity']) if baseline else r['upstream_severity'],
            enabledByDefault=r.get('upstream_baseline_enabled_by_default', r['upstream_enabled_by_default']) if baseline else r['upstream_enabled_by_default'],
        ))
    catalog.append(dict(
        ruleId=r['id'], upstreamId=r['upstream_id'], registry=r['upstream_registry'],
        title=r['problem'], severity=r['severity'], kind=r['kind'], certainty=r['certainty'],
        depth=r['depth'], activation=r['default_activation'], versions=versions,
        positiveSeed=r.get('upstream_positive_fixture_source'),
        negativeSeed=r.get('upstream_negative_fixture_source'),
        positiveHasAssertion=r.get('upstream_positive_fixture_expected_output', False),
        gap=r.get('upstream_fixture_gap'),
    ))
path = ROOT / 'modules/provider-lint/src/main/resources/dev/ktchecker/providers/lint/android-lint-catalog.json'
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + '\n')
implementation = ROOT / 'modules/provider-lint/src/main/kotlin/dev/ktchecker/providers/lint/AndroidLintProvider.kt'
impl_evidence = []
if implementation.exists():
    impl_evidence = [dict(path=str(implementation.relative_to(ROOT)), sha256=hashlib.sha256(implementation.read_bytes()).hexdigest())]
mapdata = dict(schema_version=1, provider_id='android-lint', provider_version='32.3.1/32.5.0',
    mappings=[dict(rule_id=r['id'], upstream_id=r['upstream_id'], relation='exact',
        registry=r['upstream_registry'], provider_coordinate=r['provider'],
        implementation_evidence=impl_evidence,
        gaps=['Per-ID mapping is not detector integration or target qualification.']) for r in imports])
(ROOT / 'modules/provider-lint/src/main/resources/android-lint-rule-map.json').write_text(json.dumps(mapdata, indent=2) + '\n')

core = json.loads((ROOT / 'data/rules-core.json').read_text())
compiler = [r for r in core if r['id'].startswith('ktc.compiler.') and not r['id'].startswith('ktc.compiler.jvm.')]
assert len(compiler) == 344
native_catalog = [dict(ruleId=r['id'], upstreamId=r['upstream_id'], title=r['problem'],
    severity=r['severity'], family=r.get('compiler_factory', {}).get('container', ''), provider=r['provider']) for r in compiler]
path = ROOT / 'modules/provider-native/src/main/resources/dev/ktchecker/providers/native/kotlin-native-catalog.json'
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(native_catalog, indent=2) + '\n')
implementation = ROOT / 'modules/provider-native/src/main/kotlin/dev/ktchecker/providers/native/KotlinNativeProvider.kt'
impl_evidence = []
if implementation.exists():
    impl_evidence = [dict(path=str(implementation.relative_to(ROOT)), sha256=hashlib.sha256(implementation.read_bytes()).hexdigest())]
mapdata = dict(schema_version=1, provider_id='kotlin-native', provider_version='2.4.21',
    mappings=[dict(rule_id=r['id'], upstream_id=r['upstream_id'], relation='exact',
        implementation_evidence=impl_evidence,
        gaps=['Mapping is not executable Apple-host coverage; Native M0 qualification is separate.']) for r in compiler])
(ROOT / 'modules/provider-native/src/main/resources/kotlin-native-rule-map.json').write_text(json.dumps(mapdata, indent=2) + '\n')
print(f'Frozen {len(catalog)} artifact-scoped Lint and {len(native_catalog)} common/Native/IR diagnostic mappings; zero qualification changed.')

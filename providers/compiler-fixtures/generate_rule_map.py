#!/usr/bin/env python3
"""Refresh exact mappings from reviewed metadata; never sets tested/qualified."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'modules/provider-compiler'
rows = [r for r in json.loads((ROOT / 'data/rules-core.json').read_text()) if r['id'].startswith('ktc.compiler.')]
assert len(rows) == len({r['id'] for r in rows}) == len({r['upstream_id'] for r in rows}) == 443
source_files = sorted((MODULE / 'src/main/kotlin').rglob('*.kt'))
evidence = [{'path': str(p.relative_to(ROOT)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in source_files]
mappings = []
for row in rows:
    activation = (['-Xuse-fir-experimental-checkers'] if row['upstream_id'] == 'EMPTY_RANGE' else
                  ['-Wextra'] if row['compiler_factory']['source_section'].lower() == 'extra checkers' or
                                row['upstream_id'] == 'UNREACHABLE_CODE' else [])
    activation_gap = ([f"Pinned checker registration requires explicit {activation[0]}; factory-file section alone is not activation evidence."]
                      if activation else [])
    mappings.append({
        'rule_id': row['id'], 'upstream_id': row['upstream_id'], 'relation': 'exact',
        'catalog_record_sha256': hashlib.sha256(json.dumps(row, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest(),
        'backend': row['category'].removeprefix('kotlin.compiler.'),
        'declared_severity': row['compiler_factory']['declaration_severity'],
        'kind': row['kind'], 'certainty': row['certainty'],
        'default_activation': row['default_activation'],
        'source': row['sources'][0],
        'source_section': row['compiler_factory']['source_section'],
        'known_checker_activation_flags': activation,
        'implementation_evidence': evidence,
        'gaps': [f"{row['id']} / {row['upstream_id']} is a Kotlin/Native factory at {row['sources'][0]}; "
                 "the JVM adapter does not execute it. Requires actual pinned Native target, imported compilation, "
                 "Native dependencies and per-ID fixture/link/simulator evidence; JVM mapping availability is not Native/iOS support."] if row['category'].endswith('.native') else
            ['Exact mapping availability alone does not establish per-ID fixture testing or tuple qualification'] + activation_gap,
    })
output = MODULE / 'src/main/resources/compiler-rule-map.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({'schema_version': 1, 'provider_id': 'kotlin-compiler-jvm',
                             'provider_version': '2.4.21', 'mappings': mappings}, indent=2) + '\n')
print(f'Wrote {len(mappings)} mappings; no catalog status or default changed')

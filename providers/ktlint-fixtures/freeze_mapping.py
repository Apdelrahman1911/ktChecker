#!/usr/bin/env python3
"""Freeze all individually inventoried Ktlint routes, never implementation claims."""

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    mappings = []
    for path in sorted((ROOT / "data").glob("rules-*.json")):
        for record in json.loads(path.read_text(encoding="utf-8")):
            if record["id"].startswith("ktc.maintainability.ktlint."):
                upstream = record["upstream_id"]
            else:
                aliases = [alias for alias in record.get("upstream_aliases", []) if alias.startswith("compose:")]
                if not aliases:
                    continue
                if len(aliases) != 1:
                    raise ValueError(f"ambiguous Ktlint alias: {record['id']}")
                upstream = aliases[0]
            frozen = json.dumps(record, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
            mappings.append({
                "rule_id": record["id"], "upstream_id": upstream,
                "relation": "excluded_alias" if record["default_activation"] == "excluded" else "requires_refinement" if record["depth"] != "syntax" else "exact",
                **{name: record[name] for name in ("depth", "severity", "kind", "certainty", "problem", "rationale", "default_activation")},
                "title": record["problem"], "catalog_record_sha256": hashlib.sha256(frozen).hexdigest(),
                "gaps": [record["disposition_reason"]] if record["default_activation"] == "excluded" else
                    ["Pinned Ktlint PSI frontend does not establish this catalog semantic-depth predicate; an alias is not resolved Detekt Analysis API equivalence."] if record["depth"] != "syntax" else [],
            })
    mappings.sort(key=lambda row: row["rule_id"])
    if len(mappings) != 137 or sum(row["relation"] == "exact" for row in mappings) != 131:
        raise ValueError("Ktlint catalog inventory changed; review each new/changed route explicitly")
    output = ROOT / "modules/provider-ktlint/src/main/resources/ktlint-rule-map.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({
        "schema_version": 1, "provider_id": "ktlint", "provider_version": "1.8.0",
        "inventory_sources": [
            "https://github.com/ktlint/ktlint/blob/1.8.0/ktlint-ruleset-standard/src/main/kotlin/com/pinterest/ktlint/ruleset/standard/StandardRuleSetProvider.kt",
            "https://github.com/mrmans0n/compose-rules/blob/v0.6.8/rules/ktlint/src/main/kotlin/io/nlopez/compose/rules/ktlint/ComposeRuleSetProvider.kt",
        ],
        "activation": "Owner-selected opt-in syntax profile only; mapping is not testing or compatibility qualification.",
        "mappings": mappings,
    }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

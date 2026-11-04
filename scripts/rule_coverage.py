#!/usr/bin/env python3
"""Evidence-backed coverage inventory, not a detector or release certificate.

Every catalog record is emitted for every supplied/observed context. A mapping is
not implementation evidence, a source file is not a test, and upstream tests are
not integration/qualification evidence. Local sidecars are attestations whose
references this tool verifies; they cannot authorize process execution or turn
an unreviewed compatibility tuple into a supported release.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
from typing import Any

CASE_KINDS = frozenset(("positive", "negative", "configuration", "version", "suppression", "regression"))
RELATIONS = frozenset(("exact", "requires_refinement", "excluded_alias"))
DEFAULT_CONTEXT = {
    "id": "unqualified", "target": "unknown", "source_set": "unknown",
    "kotlin_version": "unknown", "jdk_version": "unknown", "library_versions": {},
}
QUALIFICATION_IDENTITIES = (
    "target", "source_set", "module", "compilation", "kotlin_version", "compiler_version", "jdk_version",
)
QUALIFICATION_DIGESTS = (
    "model_digest", "dependency_digest", "classpath_digest", "toolchain_digest",
    "configuration_digest", "policy_digest", "compilation_members_digest",
)
UNKNOWN_IDENTITIES = frozenset(("unknown", "all", "*", "unavailable", "unqualified"))


class CoverageError(ValueError):
    """A malformed or unsafe coverage input."""


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _unique_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise CoverageError(f"duplicate JSON field {key!r}")
        result[key] = value
    return result


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_pairs)
    except (OSError, UnicodeError, ValueError) as error:
        raise CoverageError(f"{path}: {error}") from error


def repository_file(root: Path, value: Any) -> Path:
    if not isinstance(value, str) or not value or "\\" in value or "\x00" in value:
        raise CoverageError("evidence path must be a nonempty repository-relative POSIX path")
    relative = PurePosixPath(value)
    if relative.is_absolute() or any(part in (".", "..") for part in value.split("/")):
        raise CoverageError(f"unsafe repository path: {value!r}")
    path = root.joinpath(*relative.parts)
    # Reject symlinks rather than only checking the final target. Evidence must
    # not depend on mutable aliases, even aliases currently inside this repo.
    current = root
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            raise CoverageError(f"symlink evidence is not accepted: {value!r}")
    if not path.is_file() or not path.resolve().is_relative_to(root.resolve()):
        raise CoverageError(f"missing/outside repository evidence: {value!r}")
    return path


def repository_input(root: Path, path: Path) -> Path:
    """Resolve CLI/API input paths against the repository, without hiding links."""
    try:
        relative = path.relative_to(root) if path.is_absolute() else path
    except ValueError as error:
        raise CoverageError(f"input manifest must be inside repository: {path}") from error
    return repository_file(root, relative.as_posix())


def verify_references(root: Path, references: Any, label: str) -> list[dict[str, str]]:
    if not isinstance(references, list):
        raise CoverageError(f"{label}: evidence must be an array of {{path, sha256}}")
    result = []
    for reference in references:
        if not isinstance(reference, dict):
            raise CoverageError(f"{label}: evidence reference must be an object")
        path = repository_file(root, reference.get("path"))
        expected = reference.get("sha256")
        if not isinstance(expected, str):
            raise CoverageError(f"{label}: sha256 is required")
        expected = expected.removeprefix("sha256:")
        if len(expected) != 64 or any(c not in "0123456789abcdef" for c in expected):
            raise CoverageError(f"{label}: invalid sha256")
        actual = digest(path.read_bytes())
        if actual != expected:
            raise CoverageError(f"{label}: evidence digest mismatch for {reference['path']}")
        result.append({"path": reference["path"], "sha256": actual})
    return result


def context_key(context: Any) -> str:
    if not isinstance(context, dict):
        raise CoverageError("context must be an object")
    for field in ("id", "target", "source_set", "kotlin_version"):
        if not isinstance(context.get(field), str) or not context[field].strip():
            raise CoverageError(f"context.{field} is required")
    libraries = context.get("library_versions", {})
    if not isinstance(libraries, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in libraries.items()):
        raise CoverageError("context.library_versions must be a string-to-string object")
    # Same display id with a different version/classpath/configuration is a
    # separate tuple, never a merged qualification claim.
    return canonical(context)


def qualification_tuple_gaps(context: dict) -> list[str]:
    """Local qualification still needs every identity, not a display label."""
    gaps = []
    for field in QUALIFICATION_IDENTITIES:
        value = context.get(field)
        if not isinstance(value, str) or not value.strip() or value.strip().lower() in UNKNOWN_IDENTITIES:
            gaps.append(f"Qualification tuple identity {field} is unknown.")
    for field in QUALIFICATION_DIGESTS:
        value = context.get(field)
        if not isinstance(value, str):
            gaps.append(f"Qualification tuple digest {field} is missing/invalid.")
            continue
        value = value.removeprefix("sha256:")
        if len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
            gaps.append(f"Qualification tuple digest {field} is missing/invalid.")
    libraries = context.get("library_versions")
    if not isinstance(libraries, dict) or any(
        not isinstance(name, str) or not name.strip() or not isinstance(version, str) or
        not version.strip() or version.strip().lower() in UNKNOWN_IDENTITIES
        for name, version in libraries.items()
    ):
        gaps.append("Qualification tuple library identities are missing/unknown.")
    return gaps


def _boolean(record: dict, name: str, label: str) -> bool:
    value = record.get(name, False)
    if type(value) is not bool:
        raise CoverageError(f"{label}.{name} must be a boolean")
    return value


def _string_list(value: Any, label: str) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise CoverageError(f"{label} must be an array of strings")
    return value


def load_catalog(root: Path) -> dict[str, dict]:
    catalog = {}
    paths = sorted((root / "data").glob("rules-*.json"))
    if not paths:
        raise CoverageError("no data/rules-*.json catalogs")
    for path in paths:
        records = read_json(path)
        if not isinstance(records, list):
            raise CoverageError(f"{path}: catalog must be an array")
        for record in records:
            if not isinstance(record, dict) or not isinstance(record.get("id"), str):
                raise CoverageError(f"{path}: rule record/id is invalid")
            rule_id = record["id"]
            if rule_id in catalog:
                raise CoverageError(f"duplicate catalog rule {rule_id}")
            catalog[rule_id] = {
                **record, "catalog_path": path.relative_to(root).as_posix(),
                "catalog_record_sha256": digest(canonical(record).encode()),
            }
    return catalog


def load_mappings(root: Path, paths: list[Path], catalog: dict) -> dict[str, list[dict]]:
    mappings: dict[str, list[dict]] = {}
    seen = set()
    for path in paths:
        manifest = read_json(path)
        if not isinstance(manifest, dict) or manifest.get("schema_version") != 1:
            raise CoverageError(f"{path}: mapping schema_version must be 1")
        provider = manifest.get("provider_id")
        version = manifest.get("provider_version")
        if not isinstance(provider, str) or not provider or not isinstance(version, str) or not version:
            raise CoverageError(f"{path}: mapping provider id/version is required")
        if not isinstance(manifest.get("mappings"), list):
            raise CoverageError(f"{path}: mappings must be an array")
        for mapping in manifest["mappings"]:
            if not isinstance(mapping, dict) or mapping.get("rule_id") not in catalog:
                raise CoverageError(f"{path}: mapping has unknown catalog rule")
            rule_id = mapping["rule_id"]
            if (provider, version, rule_id) in seen:
                raise CoverageError(f"{path}: duplicate mapping for {provider}/{rule_id}")
            seen.add((provider, version, rule_id))
            if mapping.get("relation") not in RELATIONS:
                raise CoverageError(f"{path}: invalid mapping relation for {rule_id}")
            frozen = mapping.get("catalog_record_sha256")
            if frozen is not None:
                if not isinstance(frozen, str):
                    raise CoverageError(f"{path}: catalog_record_sha256 must be a string for {rule_id}")
                if frozen.removeprefix("sha256:") != catalog[rule_id]["catalog_record_sha256"]:
                    raise CoverageError(f"{path}: catalog drift for mapped rule {rule_id}")
            refs = verify_references(root, mapping.get("implementation_evidence", []), rule_id)
            gaps = _string_list(mapping.get("gaps", []), f"{rule_id}.gaps")
            mappings.setdefault(rule_id, []).append({
                **mapping, "provider_id": provider, "provider_version": version,
                "manifest": path.relative_to(root).as_posix(),
                "implementation_evidence": refs, "gaps": gaps,
            })
    return mappings


def load_evidence(root: Path, paths: list[Path], catalog: dict) -> tuple[dict, dict]:
    evidence = {}
    contexts = {}
    for path in paths:
        sidecar = read_json(path)
        if not isinstance(sidecar, dict) or sidecar.get("schema_version") != 1:
            raise CoverageError(f"{path}: evidence schema_version must be 1")
        context = sidecar.get("context")
        key = context_key(context)
        contexts[key] = context
        provider, version = sidecar.get("provider_id"), sidecar.get("provider_version")
        if not isinstance(provider, str) or not provider or not isinstance(version, str) or not version:
            raise CoverageError(f"{path}: evidence provider id/version is required")
        rules = sidecar.get("rules")
        if not isinstance(rules, list):
            raise CoverageError(f"{path}: evidence rules must be an array")
        for rule in rules:
            if not isinstance(rule, dict) or rule.get("rule_id") not in catalog:
                raise CoverageError(f"{path}: evidence has unknown catalog rule")
            rule_id = rule["rule_id"]
            identity = (key, rule_id, provider, version)
            if identity in evidence:
                raise CoverageError(f"{path}: duplicate evidence for {rule_id}/{context['id']}/{provider}")
            stages = {name: _boolean(rule, name, rule_id) for name in ("implemented", "tested", "qualified")}
            cases = rule.get("cases", [])
            if not isinstance(cases, list):
                raise CoverageError(f"{path}: {rule_id}.cases must be an array")
            valid_kinds = set()
            verified_cases = []
            for case in cases:
                if not isinstance(case, dict) or case.get("kind") not in CASE_KINDS:
                    raise CoverageError(f"{path}: {rule_id} invalid case kind")
                passed = _boolean(case, "passed", rule_id)
                refs = verify_references(root, case.get("evidence", []), f"{rule_id}/{case['kind']}")
                if passed and refs:
                    valid_kinds.add(case["kind"])
                verified_cases.append({**case, "evidence": refs})
            implementation = verify_references(root, rule.get("implementation_evidence", []), rule_id)
            qualification = verify_references(root, rule.get("qualification_evidence", []), rule_id)
            gaps = _string_list(rule.get("gaps", []), f"{rule_id}.gaps")
            evidence[identity] = {
                **rule, **stages, "cases": verified_cases, "case_kinds_passed": sorted(valid_kinds),
                "implementation_evidence": implementation, "qualification_evidence": qualification,
                "gaps": gaps, "sidecar": path.relative_to(root).as_posix(),
            }
    return evidence, contexts


def generate_coverage(root: Path, mapping_paths: list[Path] | None = None,
                      evidence_paths: list[Path] | None = None,
                      contexts: list[dict] | None = None) -> dict:
    root = root.resolve()
    catalog = load_catalog(root)
    mapping_paths = mapping_paths if mapping_paths is not None else sorted(root.glob("modules/provider-*/src/main/resources/*rule-map.json"))
    evidence_paths = evidence_paths if evidence_paths is not None else sorted(root.glob("providers/*-fixtures/evidence/*.json"))
    mapping_paths = [repository_input(root, path) for path in mapping_paths]
    evidence_paths = [repository_input(root, path) for path in evidence_paths]
    mappings = load_mappings(root, mapping_paths, catalog)
    evidence, observed_contexts = load_evidence(root, evidence_paths, catalog)
    chosen = {context_key(context): context for context in contexts} if contexts is not None else observed_contexts
    if not chosen:
        chosen = {context_key(DEFAULT_CONTEXT): DEFAULT_CONTEXT}
    rows = []
    for key, context in sorted(chosen.items()):
        for rule_id, catalog_rule in sorted(catalog.items()):
            excluded = catalog_rule.get("default_activation") == "excluded"
            candidates = []
            for mapping in mappings.get(rule_id, []):
                attestation = evidence.get((key, rule_id, mapping["provider_id"], mapping["provider_version"]), {})
                gaps = list(mapping["gaps"]) + list(attestation.get("gaps", []))
                exact = mapping["relation"] == "exact" and not excluded
                implemented = exact and attestation.get("implemented") is True and bool(
                    attestation.get("implementation_evidence") or mapping.get("implementation_evidence"))
                all_cases = CASE_KINDS.issubset(attestation.get("case_kinds_passed", []))
                tested = implemented and attestation.get("tested") is True and all_cases
                tuple_gaps = qualification_tuple_gaps(context)
                qualified = tested and attestation.get("qualified") is True and not tuple_gaps and not gaps and bool(attestation.get("qualification_evidence"))
                gaps += tuple_gaps
                if not exact:
                    gaps.append("Catalog semantics are excluded or require an unimplemented refinement; raw upstream reports are not this rule.")
                if not implemented:
                    gaps.append("No explicit hash-backed implementation attestation for this rule/context/provider tuple.")
                if not tested:
                    missing = sorted(CASE_KINDS - set(attestation.get("case_kinds_passed", [])))
                    gaps.append("Unqualified integration tests" + (": missing " + ", ".join(missing) if missing else ": explicit tested claim missing") + ".")
                if not qualified:
                    gaps.append("No completed compatibility/precision/performance qualification for this exact tuple.")
                candidates.append({
                    "provider_id": mapping["provider_id"], "provider_version": mapping["provider_version"],
                    "upstream_id": mapping.get("upstream_id"), "relation": mapping["relation"],
                    "manifest": mapping["manifest"], "mapped": True,
                    "implemented": implemented, "tested": tested, "qualified": qualified,
                    "case_kinds_passed": attestation.get("case_kinds_passed", []),
                    "provider_status": attestation.get("provider_status", "not_executed"),
                    "evidence_sidecar": attestation.get("sidecar"), "gaps": sorted(set(gaps)),
                })
            implemented = any(candidate["implemented"] for candidate in candidates)
            tested = any(candidate["tested"] for candidate in candidates)
            qualified = any(candidate["qualified"] for candidate in candidates)
            state = "excluded" if excluded else "qualified" if qualified else "tested_unqualified" if tested else "implemented_untested" if implemented else "unimplemented"
            rows.append({
                "rule_id": rule_id, "context": context, "context_sha256": digest(key.encode()),
                "catalog_path": catalog_rule["catalog_path"], "catalog_record_sha256": catalog_rule["catalog_record_sha256"],
                "default_activation": catalog_rule.get("default_activation", "unknown"),
                "disposition_reason": catalog_rule.get("disposition_reason", ""),
                "catalog_status": catalog_rule.get("status", "unknown"), "depth": catalog_rule.get("depth", "unknown"),
                "mapped": bool(candidates), "implemented": implemented, "tested": tested, "qualified": qualified,
                "runtime_default_enabled": False, "state": state, "providers": candidates,
                "gaps": [] if candidates else ["No provider mapping/implementation; planned catalog validation is not execution evidence."],
            })
    # Evidence without an exact provider+version mapping is still an error,
    # never quietly accepted as implementation for another provider version.
    for (_, rule_id, provider, version) in evidence:
        if not any(m["provider_id"] == provider and m["provider_version"] == version for m in mappings.get(rule_id, [])):
            raise CoverageError(f"unmapped provider/version evidence: {provider}@{version}/{rule_id}")
    active = [row for row in rows if row["default_activation"] != "excluded"]
    return {
        "schema_version": 1, "kind": "ktchecker_evidence_inventory",
        "all_requested_cells_qualified": bool(active) and all(row["qualified"] for row in active),
        # This inventory has no authenticated required release plan, matrix
        # completeness proof or release-gate inputs. A selected local JVM
        # context cannot certify Android/iOS/Desktop (or another release).
        "release_qualified": False,
        "release_qualification_reason": "No independently checked complete required release matrix and release gates were supplied; requested/observed local cells are not a release plan.",
        "catalog_records": len(catalog), "contexts": [chosen[key] for key in sorted(chosen)],
        "row_count": len(rows), "summary": {name: sum(row[name] for row in rows) for name in ("mapped", "implemented", "tested", "qualified")},
        "states": {state: sum(row["state"] == state for row in rows) for state in sorted({row["state"] for row in rows})},
        "evidence_trust": "Local hash-verified attestations, not an authorization or independent release certificate.",
        "rows": rows,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--mapping", type=Path, action="append", help="Override discovered mapping manifests; paths are relative to --root (repeatable).")
    parser.add_argument("--evidence", type=Path, action="append", help="Override discovered evidence sidecars; paths are relative to --root (repeatable).")
    parser.add_argument("--context", type=Path, action="append", help="JSON context object; emit every catalog rule in each tuple.")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--require-tested", action="store_true", help="Fail unless all nonexcluded records have all six test kinds.")
    parser.add_argument("--require-qualified", action="store_true", help="Fail unless all nonexcluded requested report cells are explicitly qualified; this never certifies a release.")
    args = parser.parse_args(argv)
    try:
        contexts = [read_json(path) for path in args.context] if args.context is not None else None
        output = generate_coverage(args.root, args.mapping, args.evidence, contexts)
        rendered = json.dumps(output, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
        else:
            sys.stdout.write(rendered)
        required = [row for row in output["rows"] if row["default_activation"] != "excluded"]
        if args.require_qualified and not output["all_requested_cells_qualified"]:
            return 1
        if args.require_tested and any(not row["tested"] for row in required):
            return 1
        return 0
    except (CoverageError, OSError, TypeError) as error:
        print(f"rule coverage error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

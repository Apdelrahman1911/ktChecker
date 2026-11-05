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
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import sys
import tempfile
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
    """Canonicalize only a root alias, preserving every unresolved descendant."""
    root = root.resolve()
    relative = path
    if path.is_absolute():
        if ".." in path.parts:
            raise CoverageError(f"unsafe repository input path: {path}")
        try:
            relative = path.relative_to(root)
        except ValueError:
            # macOS /var versus /private/var and Windows short/long directory
            # names can identify the same selected repository root. Resolve the
            # outermost matching root prefix, NEVER the whole input path. The
            # outermost order also preserves an in-repo link pointing back to
            # the root, so that repository_file still rejects that link.
            for ancestor in reversed(path.parents):
                try:
                    same_root = ancestor.resolve(strict=True) == root
                except (OSError, RuntimeError):
                    continue
                if same_root:
                    relative = path.relative_to(ancestor)
                    break
            else:
                raise CoverageError(f"input manifest must be inside repository: {path}")
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
    # Every context field participates, including native helper and runtime
    # image/source digests. Different filenames do not isolate identical tuples,
    # even when a prior sidecar's current-stage flags have been withdrawn.
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
        "inventory_status": "valid", "inventory_valid": True,
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


def publish_inventory(path: Path, output: dict) -> None:
    """Replace one explicitly selected report atomically, never expose half a JSON file.

    This is a report publisher, not a crash-durable evidence ledger. Concurrent
    invocations must select distinct output paths. Callers must still check the
    command result and inventory_valid; an old file is not evidence of a new run.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n",
                                         prefix=".ktchecker-coverage-", suffix=".tmp",
                                         dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(output, stream, indent=2, sort_keys=True, ensure_ascii=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def catalog_visibility(root: Path, evidence_paths: list[Path] | None = None,
                       contexts: list[dict] | None = None, *,
                       context_inputs_available: bool = True) -> dict:
    """Declared catalog/context metadata only, never a substitute for rejected evidence.

    Used only to enrich a failed CLI report. The strict generator still rejects
    the original input and no attestation, mapping, reference or stage is salvaged.
    Context envelopes declare a scope; they neither validate their evidence nor
    establish a current project or required release matrix. Error text is unused.
    """
    visibility = {
        "schema_version": 1, "kind": "ktchecker_declared_catalog_visibility",
        "status": "unavailable", "catalog_valid": False, "context_scope_known": False,
        "context_scope_source": None,
        "evidence_valid": False, "current_coverage_known": False,
        "all_requested_cells_qualified": False, "release_qualified": False,
        "catalog_records": None, "excluded_records": None, "candidate_records": None,
        "catalog_rules": None, "contexts": None, "context_count": None,
        "row_count": None, "excluded_cells": None, "candidate_cells": None, "rows": None,
        "gaps": ["declared_catalog_unavailable"],
        "scope": "Independently read declared inventory only; not current implementation, testing, qualification or a complete release matrix.",
    }
    try:
        root = root.resolve()
        # Unlike an evidence fallback, a catalog basis must stand on its own.
        # Reject aliases in catalog descendants before reading their contents.
        for path in sorted((root / "data").glob("rules-*.json")):
            repository_input(root, path)
        catalog = load_catalog(root)
    except (CoverageError, OSError, TypeError, UnicodeError):
        return visibility
    rules = [{
        "rule_id": rule_id, "catalog_path": rule["catalog_path"],
        "catalog_record_sha256": rule["catalog_record_sha256"],
        "default_activation": rule.get("default_activation", "unknown"),
        "disposition_reason": rule.get("disposition_reason", ""),
        "catalog_status": rule.get("status", "unknown"), "depth": rule.get("depth", "unknown"),
        "catalog_state": "excluded" if rule.get("default_activation") == "excluded" else "included",
    } for rule_id, rule in sorted(catalog.items())]
    excluded = sum(rule["catalog_state"] == "excluded" for rule in rules)
    visibility.update({
        "status": "catalog_only_context_scope_unavailable", "catalog_valid": True,
        "catalog_records": len(rules), "excluded_records": excluded,
        "candidate_records": len(rules) - excluded, "catalog_rules": rules,
        "gaps": ["declared_context_scope_unavailable"],
    })
    # A missing/malformed explicit context is not permission to select observed
    # contexts or invent the default tuple. Preserve the independently known catalog.
    if not context_inputs_available:
        return visibility
    try:
        if contexts is not None:
            chosen = {context_key(context): context for context in contexts}
            context_source = "explicit"
        else:
            paths = evidence_paths if evidence_paths is not None else sorted(root.glob("providers/*-fixtures/evidence/*.json"))
            chosen = {}
            context_source = "observed_declarations"
            for path in paths:
                sidecar = read_json(repository_input(root, path))
                if not isinstance(sidecar, dict) or sidecar.get("schema_version") != 1:
                    raise CoverageError("invalid declared context envelope")
                for field in ("provider_id", "provider_version"):
                    if not isinstance(sidecar.get(field), str) or not sidecar[field]:
                        raise CoverageError("invalid declared provider envelope")
                if not isinstance(sidecar.get("rules"), list):
                    raise CoverageError("invalid declared rules envelope")
                context = sidecar.get("context")
                chosen[context_key(context)] = context
        if not chosen:
            chosen = {context_key(DEFAULT_CONTEXT): DEFAULT_CONTEXT}
            context_source = "default_unqualified"
    except (CoverageError, OSError, TypeError, UnicodeError):
        return visibility
    # Rows join exact context objects and catalog records by their unchanged
    # canonical identities. No provider/result data or historical passed cases
    # enters this projection; even unaffected cells remain unevaluated here.
    rows = [{
        "rule_id": rule["rule_id"], "catalog_record_sha256": rule["catalog_record_sha256"],
        "context_sha256": digest(key.encode()),
        "state": "excluded" if rule["catalog_state"] == "excluded" else "current_evidence_unavailable",
        "evidence_status": "unavailable", "evaluation_state": "incomplete",
        "mapping_reference_error_indexes": [], "mapped": None,
        "implemented": False, "tested": False, "qualified": False, "runtime_default_enabled": False,
    } for key in sorted(chosen) for rule in rules]
    visibility.update({
        "status": "declared_inventory_evidence_unavailable", "context_scope_known": True,
        "context_scope_source": context_source,
        "contexts": [chosen[key] for key in sorted(chosen)], "context_count": len(chosen),
        "row_count": len(rows), "excluded_cells": excluded * len(chosen),
        "candidate_cells": (len(rules) - excluded) * len(chosen), "rows": rows,
        "gaps": ["strict_coverage_inputs_invalid_or_unreadable", "current_evidence_not_evaluated_in_visibility"],
    })
    return visibility


def unavailable_inventory(status: str, visibility: dict | None = None, *,
                          failure_diagnostics: dict | None = None) -> dict:
    """Evidence counts stay unknown; optional declared metadata cannot restore credit."""
    output = {
        "schema_version": 1, "kind": "ktchecker_evidence_inventory",
        "inventory_status": status, "inventory_valid": False,
        "all_requested_cells_qualified": False, "release_qualified": False,
        "release_qualification_reason": "The current evidence inventory has not been validated.",
        "catalog_records": None, "contexts": None, "row_count": None,
        "summary": None, "states": None, "rows": None,
        "catalog_visibility": visibility,
        "gaps": ["coverage_inventory_generation_pending" if status == "pending"
                 else "coverage_inputs_invalid_or_unreadable"],
    }
    if failure_diagnostics is not None:
        output["failure_diagnostics"] = failure_diagnostics
    return output


def _diagnostic_relative_path(value: Any) -> str | None:
    """A literal repository-relative label, never an exception or outside path."""
    if not isinstance(value, str) or not value or "\\" in value or "\x00" in value:
        return None
    relative = PurePosixPath(value)
    if PureWindowsPath(value).drive or relative.is_absolute() or any(part in (".", "..") for part in value.split("/")):
        return None
    return relative.as_posix()


def mapping_reference_diagnostics(root: Path, mapping_paths: list[Path] | None = None,
                                  visibility: dict | None = None) -> dict:
    """Reporting-only observations after rejection; never salvage coverage credit.

    Visit every safely readable mapping and reference, independent of the strict
    loader's first error. Labels are literal repository-relative metadata; raw
    exceptions, invalid values and outside paths are not serialized. Source reads
    are serial and cached by literal path for this diagnostic pass only. A complete
    mapping scan does not validate sidecars or reverse the original rejection.
    """
    visible = visibility if isinstance(visibility, dict) else {}
    declared_rules = visible.get("catalog_rules")
    catalog = {rule["rule_id"]: rule for rule in declared_rules} if isinstance(declared_rules, list) else None
    scan_complete = catalog is not None
    errors = []
    reference_count = 0
    manifest_count = None
    source_digests = {}
    seen = set()

    def add(code, manifest=None, input_index=None, mapping_index=None, rule_id=None,
            reference_index=None, reference_field=None, reference_path=None, *, incomplete=False):
        nonlocal scan_complete
        if incomplete:
            scan_complete = False
        errors.append({
            "error_index": len(errors), "code": code, "mapping_manifest": manifest,
            "mapping_input_index": input_index, "mapping_index": mapping_index,
            "rule_id": rule_id, "reference_index": reference_index,
            "reference_field": reference_field, "reference_path": reference_path,
        })

    if catalog is None:
        add("declared_catalog_scope_unavailable", incomplete=True)
    try:
        root = root.resolve()
        paths = mapping_paths if mapping_paths is not None else sorted(root.glob("modules/provider-*/src/main/resources/*rule-map.json"))
        manifest_count = len(paths)
    except (OSError, RuntimeError, TypeError, UnicodeError):
        paths = []
        add("mapping_input_scan_unavailable", incomplete=True)
    for input_index, input_path in enumerate(paths):
        manifest_label = None
        if not isinstance(input_path, Path):
            add("mapping_input_invalid", input_index=input_index, incomplete=True)
            continue
        try:
            label = input_path.relative_to(root).as_posix() if input_path.is_absolute() else input_path.as_posix()
            manifest_label = _diagnostic_relative_path(label)
        except ValueError:
            pass
        try:
            path = repository_input(root, input_path)
            manifest_label = path.relative_to(root).as_posix()
            manifest = read_json(path)
        except (CoverageError, OSError, RuntimeError, TypeError, UnicodeError):
            add("mapping_manifest_unavailable", manifest_label, input_index, incomplete=True)
            continue
        if not isinstance(manifest, dict):
            add("mapping_manifest_invalid", manifest_label, input_index, incomplete=True)
            continue
        provider, version = manifest.get("provider_id"), manifest.get("provider_version")
        envelope_valid = (manifest.get("schema_version") == 1 and isinstance(provider, str) and bool(provider)
                          and isinstance(version, str) and bool(version))
        if not envelope_valid:
            add("mapping_manifest_invalid", manifest_label, input_index, incomplete=True)
        mappings = manifest.get("mappings")
        if not isinstance(mappings, list):
            add("mapping_records_invalid", manifest_label, input_index, incomplete=True)
            continue
        for mapping_index, mapping in enumerate(mappings):
            if not isinstance(mapping, dict):
                add("mapping_record_invalid", manifest_label, input_index, mapping_index, incomplete=True)
                continue
            declared_id = mapping.get("rule_id")
            rule_id = declared_id if isinstance(declared_id, str) and catalog is not None and declared_id in catalog else None
            if catalog is not None and rule_id is None:
                add("mapping_rule_invalid", manifest_label, input_index, mapping_index, incomplete=True)
            if envelope_valid and rule_id is not None:
                identity = (provider, version, rule_id)
                if identity in seen:
                    add("mapping_duplicate_tuple", manifest_label, input_index, mapping_index, rule_id, incomplete=True)
                seen.add(identity)
            relation = mapping.get("relation")
            if not isinstance(relation, str) or relation not in RELATIONS:
                add("mapping_relation_invalid", manifest_label, input_index, mapping_index, rule_id, incomplete=True)
            frozen = mapping.get("catalog_record_sha256")
            if frozen is not None:
                if not isinstance(frozen, str):
                    add("catalog_reference_digest_invalid", manifest_label, input_index, mapping_index, rule_id,
                        reference_field="catalog_record_sha256", incomplete=True)
                elif rule_id is not None and frozen.removeprefix("sha256:") != catalog[rule_id]["catalog_record_sha256"]:
                    add("catalog_reference_digest_mismatch", manifest_label, input_index, mapping_index, rule_id,
                        reference_field="catalog_record_sha256",
                        reference_path=_diagnostic_relative_path(catalog[rule_id]["catalog_path"]))
            gaps = mapping.get("gaps", [])
            if not isinstance(gaps, list) or any(not isinstance(gap, str) for gap in gaps):
                add("mapping_gap_metadata_invalid", manifest_label, input_index, mapping_index, rule_id, incomplete=True)
            references = mapping.get("implementation_evidence", [])
            if not isinstance(references, list):
                add("reference_array_invalid", manifest_label, input_index, mapping_index, rule_id,
                    reference_field="implementation_evidence", incomplete=True)
                continue
            for reference_index, reference in enumerate(references):
                reference_count += 1
                location = (manifest_label, input_index, mapping_index, rule_id, reference_index, "implementation_evidence")
                if not isinstance(reference, dict):
                    add("reference_object_invalid", *location, incomplete=True)
                    continue
                reference_label = _diagnostic_relative_path(reference.get("path"))
                reference_file = None
                if reference_label is None:
                    add("reference_path_invalid", *location, incomplete=True)
                else:
                    try:
                        reference_file = repository_file(root, reference_label)
                    except (CoverageError, OSError, RuntimeError, TypeError, UnicodeError):
                        add("reference_path_unavailable", *location, reference_path=reference_label, incomplete=True)
                expected = reference.get("sha256")
                if isinstance(expected, str):
                    expected = expected.removeprefix("sha256:")
                if not isinstance(expected, str) or len(expected) != 64 or any(character not in "0123456789abcdef" for character in expected):
                    add("reference_digest_invalid", *location, reference_path=reference_label, incomplete=True)
                    continue
                if reference_file is not None:
                    if reference_label not in source_digests:
                        try:
                            source_digests[reference_label] = digest(reference_file.read_bytes())
                        except (OSError, TypeError, UnicodeError):
                            source_digests[reference_label] = None
                    actual = source_digests[reference_label]
                    if actual is None:
                        add("reference_read_unavailable", *location, reference_path=reference_label, incomplete=True)
                    elif actual != expected:
                        add("reference_digest_mismatch", *location, reference_path=reference_label)
    by_rule = {}
    for index, error in enumerate(errors):
        if error["rule_id"] is not None:
            by_rule.setdefault(error["rule_id"], []).append(index)
    for row in visible.get("rows") or []:
        row["mapping_reference_error_indexes"] = list(by_rule.get(row["rule_id"], []))
    return {
        "schema_version": 1, "kind": "ktchecker_mapping_reference_diagnostics", "status": "incomplete",
        "current_coverage_known": False, "mapping_reference_scan_complete": scan_complete,
        "mapping_manifest_count": manifest_count, "mapping_reference_count": reference_count,
        "mapping_reference_error_count": len(errors), "mapping_reference_errors": errors,
        "declared_catalog_records": visible.get("catalog_records"),
        "declared_context_count": visible.get("context_count"), "declared_row_count": visible.get("row_count"),
        "scope": "Mapping-input observations only; no accepted evidence, current-stage counts, hash rewrites or execution authority.",
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
    contexts = None
    context_inputs_available = args.context is None
    try:
        # Invalidate a previous run BEFORE inspecting inputs. A crash or rejected
        # digest must not leave yesterday's counts masquerading as this attempt.
        # Publishing failure itself is an error; no filesystem success is assumed.
        if args.output:
            publish_inventory(args.output, unavailable_inventory("pending"))
        contexts = [read_json(path) for path in args.context] if args.context is not None else None
        context_inputs_available = True
        output = generate_coverage(args.root, args.mapping, args.evidence, contexts)
        if args.output:
            publish_inventory(args.output, output)
        else:
            sys.stdout.write(json.dumps(output, indent=2, sort_keys=True, ensure_ascii=False) + "\n")
        required = [row for row in output["rows"] if row["default_activation"] != "excluded"]
        if args.require_qualified and not output["all_requested_cells_qualified"]:
            return 1
        if args.require_tested and any(not row["tested"] for row in required):
            return 1
        return 0
    except (CoverageError, OSError, TypeError, UnicodeError) as error:
        if args.output:
            try:
                # Declared rows and typed mapping-input diagnostics never restore
                # evidence credit or serialize arbitrary exception/outside-path text.
                visibility = catalog_visibility(args.root, args.evidence, contexts,
                                                context_inputs_available=context_inputs_available)
                diagnostics = mapping_reference_diagnostics(args.root, args.mapping, visibility)
                publish_inventory(args.output, unavailable_inventory("unavailable", visibility,
                                                                     failure_diagnostics=diagnostics))
            except (OSError, TypeError, UnicodeError):
                print("rule coverage error: report publication failed; do not use an existing output as current evidence", file=sys.stderr)
        print(f"rule coverage error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Reproduce the checked-in current coverage selection, not rule qualification.

Inputs are repository-relative, explicitly selected and hash-pinned. No provider,
compiler, Gradle, network or discovered evidence is executed/selected. Use a quiet
checkout and distinct output paths for concurrent invocations; this is not a
hostile same-UID boundary or a crash-durable execution receipt.
"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import sys
import tempfile
import types

SELECTION = "providers/coverage/current-inputs.json"
DEFAULT_OUTPUT = "build/reports/rule-coverage.json"
MAX_INPUT_BYTES = 16 * 1024 * 1024
STAGES = ("implemented", "tested", "qualified")


class SelectionError(ValueError):
    """The fixed current selection or its output could not be verified."""


def require(condition, code):
    if not condition:
        raise SelectionError(code)


def sha(body):
    return hashlib.sha256(body).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON field")
        result[key] = value
    return result


def decode(body):
    def invalid_constant(_value):
        raise SelectionError("non-finite JSON number")
    return json.loads(body, object_pairs_hook=unique_pairs, parse_constant=invalid_constant)


def relative_path(value):
    require(isinstance(value, str) and bool(value), "repository-relative path required")
    require("\\" not in value and "\x00" not in value, "unsafe repository path")
    path = PurePosixPath(value)
    require(not path.is_absolute() and not PureWindowsPath(value).drive
            and all(part not in ("", ".", "..") for part in value.split("/")),
            "unsafe repository path")
    return path


def repository_path(root, value, *, existing=True):
    relative = relative_path(value)
    current = root
    for part in relative.parts:
        current /= part
        require(not current.is_symlink(), "symlink repository descendant")
    require(current.resolve().is_relative_to(root), "outside repository path")
    if existing:
        require(current.is_file(), "repository input missing or not a regular file")
    elif current.exists():
        require(current.is_file(), "output is not a regular file")
    return current


def read_bytes(path, limit=MAX_INPUT_BYTES):
    with path.open("rb") as stream:
        body = stream.read(limit + 1)
    require(len(body) <= limit, "input size limit exceeded")
    return body


def valid_digest(value):
    return (isinstance(value, str) and len(value) == 64
            and all(character in "0123456789abcdef" for character in value))


def pinned(root, item, fingerprints):
    require(isinstance(item, dict), "input pin must be an object")
    path = repository_path(root, item.get("path"))
    require(item["path"] not in fingerprints, "duplicate input path")
    require(type(item.get("bytes")) is int and 0 <= item["bytes"] <= MAX_INPUT_BYTES
            and valid_digest(item.get("sha256")), "invalid input size/digest pin")
    body = read_bytes(path)
    require(len(body) == item["bytes"] and sha(body) == item["sha256"],
            "pinned input changed: " + item["path"])
    fingerprints[item["path"]] = (len(body), sha(body))
    return body


def referenced_pairs(document, *, evidence):
    """The strict reporter's reference fields, not arbitrary archival metadata."""
    result = set()
    records = document.get("rules" if evidence else "mappings")
    require(isinstance(records, list), "invalid selected manifest records")
    for record in records:
        require(isinstance(record, dict), "invalid selected manifest record")
        groups = [record.get("implementation_evidence", [])]
        if evidence:
            groups.append(record.get("qualification_evidence", []))
            cases = record.get("cases", [])
            require(isinstance(cases, list), "invalid selected cases")
            for case in cases:
                require(isinstance(case, dict), "invalid selected case")
                groups.append(case.get("evidence", []))
        for references in groups:
            require(isinstance(references, list), "invalid reference array")
            for reference in references:
                require(isinstance(reference, dict), "invalid reference")
                path = relative_path(reference.get("path")).as_posix()
                expected = reference.get("sha256")
                require(isinstance(expected, str), "missing reference digest")
                expected = expected.removeprefix("sha256:")
                require(valid_digest(expected), "invalid reference digest")
                result.add((path, expected))
    return result


def checked_selection(root):
    body = read_bytes(repository_path(root, SELECTION), 1024 * 1024)
    selection = decode(body)
    require(isinstance(selection, dict)
            and selection.get("schema") == "ktchecker/current-coverage-selection/1",
            "unsupported current selection")
    fingerprints = {SELECTION: (len(body), sha(body))}
    catalog, contexts, references, selected_contexts = {}, {}, set(), set()
    for group in ("catalogs", "mappings", "selected_evidence", "contexts", "reference_files"):
        entries = selection.get(group)
        require(isinstance(entries, list) and (entries or group == "reference_files"),
                "missing or empty explicit input group: " + group)
        for item in entries:
            body = pinned(root, item, fingerprints)
            if group == "reference_files":
                continue
            document = decode(body)
            if group == "catalogs":
                require(isinstance(document, list), "catalog is not an array")
                for record in document:
                    require(isinstance(record, dict) and isinstance(record.get("id"), str),
                            "invalid catalog record")
                    identifier = record["id"]
                    require(identifier not in catalog, "duplicate catalog ID")
                    catalog[identifier] = {"sha256": sha(canonical(record)),
                                           "activation": record.get("default_activation", "unknown")}
            elif group == "contexts":
                require(isinstance(document, dict) and document.get("id") == item.get("context_id"),
                        "context ID mismatch")
                identity = sha(canonical(document))
                require(identity == item.get("context_sha256") and identity not in contexts,
                        "changed or duplicate complete context")
                contexts[identity] = document
            else:
                require(isinstance(document, dict), "selected manifest is not an object")
                references.update(referenced_pairs(document, evidence=group == "selected_evidence"))
                if group == "selected_evidence":
                    selected_contexts.add(sha(canonical(document.get("context"))))

    # The unchanged reporter discovers catalogs only. Reject added/removed files
    # rather than permitting that discovery to change the pinned catalog scope.
    require({path.relative_to(root).as_posix() for path in (root / "data").glob("rules-*.json")}
            == {item["path"] for item in selection["catalogs"]}, "catalog file set changed")
    require(references == {(item["path"], item["sha256"]) for item in selection["reference_files"]},
            "reference closure differs from pinned inputs")
    require(selected_contexts <= contexts.keys(), "selected evidence has an undeclared context")
    reporter_pin = selection.get("reporter")
    require(isinstance(reporter_pin, dict) and reporter_pin.get("path") == "scripts/rule_coverage.py",
            "fixed strict reporter required")
    reporter_source = pinned(root, reporter_pin, fingerprints)
    withdrawal_pin = selection.get("withdrawals")
    require(isinstance(withdrawal_pin, dict)
            and withdrawal_pin.get("path") == "providers/coverage/withdrawals.json", "fixed withdrawals required")
    withdrawals = decode(pinned(root, withdrawal_pin, fingerprints))
    require(isinstance(withdrawals, dict)
            and withdrawals.get("schema") == "ktchecker/repository-coverage-withdrawals/1",
            "unsupported withdrawals")
    scope = selection.get("scope")
    require(isinstance(scope, dict), "scope required")
    for name in ("catalog_records", "contexts", "rows", "withdrawn_provider_rule_context_cells"):
        require(type(scope.get(name)) is int and scope[name] >= 0, "invalid scope count")
    require(scope["catalog_records"] == len(catalog) > 0 and scope["contexts"] == len(contexts) > 0
            and scope["rows"] == len(catalog) * len(contexts), "catalog/context scope changed")
    historical = set()
    envelopes = withdrawals.get("historical_envelopes")
    require(isinstance(envelopes, list), "historical envelope identities required")
    for envelope in envelopes:
        require(isinstance(envelope, dict) and envelope.get("selected_for_current_evidence") is False,
                "historical envelope must remain unselected")
        path = relative_path(envelope.get("repository_path")).as_posix()
        for name in ("original_archive", "integration_variant_at_selection"):
            variant = envelope.get(name)
            require(isinstance(variant, dict) and valid_digest(variant.get("sha256"))
                    and type(variant.get("bytes")) is int and variant["bytes"] >= 0,
                    "historical byte identity required")
            historical.add((path, variant["sha256"]))
    require(not {item["path"] for item in selection["selected_evidence"]}
            & {path for path, _digest in historical}, "withdrawn evidence reselected")
    cells, identities = withdrawals.get("cells"), set()
    require(isinstance(cells, list) and len(cells) == scope["withdrawn_provider_rule_context_cells"]
            == withdrawals.get("withdrawn_provider_rule_context_cells"), "withdrawal count changed")
    for cell in cells:
        require(isinstance(cell, dict), "invalid withdrawal cell")
        identity = tuple(cell.get(name) for name in ("context_sha256", "rule_id", "provider_id", "provider_version"))
        require(all(isinstance(value, str) and value for value in identity)
                and identity not in identities, "invalid or duplicate withdrawal identity")
        require(identity[0] in contexts and identity[1] in catalog
                and cell.get("context_id") == contexts[identity[0]]["id"], "withdrawal scope changed")
        require((cell.get("sidecar_path"), cell.get("sidecar_sha256")) in historical,
                "withdrawal historical identity missing")
        require(all(cell.get(name) is False for name in (
            "current_evidence_selected", "current_implemented", "current_tested", "current_qualified",
            "stage_transfer_allowed", "scope_reduced", "disabled"))
            and cell.get("all_catalog_rule_rows_and_complete_context_retained") is True,
            "withdrawal cannot transfer credit or reduce scope")
        identities.add(identity)
    return {"selection": selection, "fingerprints": fingerprints, "catalog": catalog,
            "contexts": contexts, "withdrawals": cells, "reporter_source": reporter_source}


def load_reporter(root, source):
    # Compile the already checked source bytes: do not consume an unrelated .pyc
    # merely because its timestamp/size happen to match the selected .py file.
    module = types.ModuleType("_ktchecker_current_rule_coverage")
    module.__file__ = str(root / "scripts/rule_coverage.py")
    exec(compile(source, module.__file__, "exec"), module.__dict__)
    return module


def reconcile(report, checked):
    catalog, contexts = checked["catalog"], checked["contexts"]
    require(isinstance(report, dict) and report.get("inventory_valid") is True
            and report.get("inventory_status") == "valid"
            and report.get("kind") == "ktchecker_evidence_inventory", "strict report is not valid")
    require(report.get("release_qualified") is False
            and report.get("all_requested_cells_qualified") is False, "unexpected qualification claim")
    declared = report.get("contexts")
    require(isinstance(declared, list) and len(declared) == len(contexts)
            and {sha(canonical(context)): context for context in declared} == contexts,
            "report complete context set changed")
    rows = report.get("rows")
    require(report.get("catalog_records") == len(catalog) and isinstance(rows, list)
            and len(rows) == report.get("row_count") == len(catalog) * len(contexts), "report scope changed")
    index = {}
    for row in rows:
        require(isinstance(row, dict), "invalid report row")
        key = (row.get("context_sha256"), row.get("rule_id"))
        require(key[0] in contexts and key[1] in catalog and key not in index,
                "duplicate or extra report identity")
        require(row.get("context") == contexts[key[0]]
                and row.get("catalog_record_sha256") == catalog[key[1]]["sha256"]
                and row.get("default_activation") == catalog[key[1]]["activation"], "report row metadata changed")
        require(row.get("runtime_default_enabled") is False
                and all(type(row.get(name)) is bool for name in ("mapped",) + STAGES), "invalid report stage/activation")
        index[key] = row
    require(set(index) == {(context, rule) for context in contexts for rule in catalog}, "missing report identities")
    require(report.get("summary") == {name: sum(row[name] for row in rows) for name in ("mapped",) + STAGES}
            and report.get("states") == dict(Counter(row["state"] for row in rows)), "report totals disagree")
    for cell in checked["withdrawals"]:
        row = index[(cell["context_sha256"], cell["rule_id"])]
        candidates = row.get("providers")
        require(isinstance(candidates, list), "missing provider rows")
        matches = [candidate for candidate in candidates if isinstance(candidate, dict)
                   and candidate.get("provider_id") == cell["provider_id"]
                   and candidate.get("provider_version") == cell["provider_version"]]
        require(len(matches) == 1, "withdrawn provider row not retained exactly once")
        candidate = matches[0]
        require(all(candidate.get(stage) is False for stage in STAGES)
                and candidate.get("evidence_sidecar") is None
                and candidate.get("provider_status") == "not_executed", "historical stage transferred")


def unavailable(status):
    return {"schema_version": 1, "kind": "ktchecker_evidence_inventory",
            "inventory_status": status, "inventory_valid": False,
            "all_requested_cells_qualified": False, "release_qualified": False,
            "release_qualification_reason": "The current evidence inventory has not been validated.",
            "catalog_records": None, "contexts": None, "row_count": None,
            "summary": None, "states": None, "rows": None, "catalog_visibility": None,
            "gaps": ["current_coverage_selection_" + status]}


def publish(root, relative, value):
    path = repository_path(root, relative, existing=False)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n",
                                         prefix=".ktchecker-current-coverage-", suffix=".tmp",
                                         dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(value, stream, indent=2, sort_keys=True, ensure_ascii=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default=DEFAULT_OUTPUT,
                        help="Repository-relative JSON output below build/ (default: %(default)s).")
    args = parser.parse_args(argv)
    output_safe = False
    try:
        root = Path(__file__).resolve(strict=True).parents[1]
        require(root.is_dir(), "repository root is not a directory")
        output = relative_path(args.output)
        require(len(output.parts) >= 2 and output.parts[0] == "build" and output.suffix == ".json",
                "output must be a repository-relative JSON file below build/")
        repository_path(root, args.output, existing=False)
        output_safe = True
        # Invalidate yesterday's counts before parsing the selector or checking
        # any pin. A preflight/reporter/reconciliation failure cannot retain them.
        publish(root, args.output, unavailable("pending"))
        before = checked_selection(root)
        reporter = load_reporter(root, before["reporter_source"])
        selected = before["selection"]
        report = reporter.generate_coverage(
            root, [Path(item["path"]) for item in selected["mappings"]],
            [Path(item["path"]) for item in selected["selected_evidence"]], list(before["contexts"].values()))
        reconcile(report, before)
        after = checked_selection(root)
        require(before["fingerprints"] == after["fingerprints"], "inputs changed during coverage generation")
        publish(root, args.output, report)
        return 0
    except Exception as error:
        if output_safe:
            try:
                publish(root, args.output, unavailable("unavailable"))
            except Exception:
                print("current coverage error: invalid output publication failed; do not use an existing output", file=sys.stderr)
        print(f"current coverage error: {error}; do not use an existing output as evidence for this attempt", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Publish the explicit 1665 declared ledger, never current execution coverage.

Only pinned catalog SOURCE, complete context metadata, the unchanged visibility
reporter and explicitly named bookkeeping DATA are read. Legacy artifact paths
are opaque metadata: their targets are never opened, hashed, statted or discovered.
Exit zero means this declared ledger was produced, NOT inventory_valid or rule
qualification. Use a quiet checkout; this is not a hostile same-UID boundary.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import sys
import tempfile
import types

SELECTION = "providers/coverage/current-inputs-1665-v1.json"
OUTPUT_DIRECTORY = "build/declared-coverage-1665-v1"
DEFAULT_OUTPUT = OUTPUT_DIRECTORY + "/inventory.json"
CONTRACT_ID = "ktchecker/owner-full-catalog/1665-u2/1"
IDS_SHA256 = "121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5"
PREDECESSOR_IDS_SHA256 = "fc45c20ddecf87113dc392a3127f4dfa9d2dd75177a71252f8fd9c67929e316b"
PREDECESSOR_RECORDS_SHA256 = "ecf306be6eca28ec1afbadc56641492418da39a787f401ed5179cc020802346c"
ADDED_RULE_IDS = frozenset(("ktc.compiler.common.error_suppression",
                            "ktc.compiler.common.inapplicable_operator_modifier"))
DENIED_REFERENCE_PATHS = frozenset(
    "providers/platform-fixtures/runs/2026-11-04T08-52-15Z-lint32.3.1-xml/" + name + ".json"
    for name in ("positive", "negative", "suppression")
)
EXPECTED_SCOPE = {"catalog_records": 1665, "contexts": 8, "rows": 13320,
                  "withdrawn_provider_rule_context_cells": 450}
LEGACY_SELECTION_PIN = {
    "path": "providers/coverage/current-inputs.json", "bytes": 19723,
    "sha256": "08d88559f95f86557efaa9b7663c8b16e66991f3548d119ec71af1b756f4b7a3",
}
FIXED_FILES = {
    "baseline": "providers/coverage/baselines/catalog-1665-v1.json",
    "withdrawals": "providers/coverage/withdrawals.json",
    "legacy_disposition": "providers/coverage/legacy-evidence-1665-v1.json",
    "historical_inventory_reference": "providers/coverage/historical-inventory-1663-c364.json",
    "reporter": "scripts/rule_coverage.py", "runner": "scripts/run_declared_coverage.py",
}
MAX_INPUT_BYTES = 16 * 1024 * 1024
MAX_OUTPUT_BYTES = 128 * 1024 * 1024
STAGES = ("implemented", "tested", "qualified")


class LedgerError(ValueError):
    """Declared scope or bookkeeping inputs did not match their explicit contract."""


def require(condition, code):
    if not condition:
        raise LedgerError(code)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def sha(body):
    return hashlib.sha256(body).hexdigest()


def decode(body):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "duplicate JSON field")
            result[key] = value
        return result

    def invalid_constant(_value):
        raise LedgerError("non-finite JSON value")

    return json.loads(body, object_pairs_hook=pairs, parse_constant=invalid_constant)


def relative_path(value):
    require(isinstance(value, str) and bool(value), "repository-relative path required")
    require("\\" not in value and "\x00" not in value, "unsafe repository path")
    result = PurePosixPath(value)
    require(not result.is_absolute() and not PureWindowsPath(value).drive
            and all(part not in ("", ".", "..") for part in value.split("/")),
            "unsafe repository path")
    return result


def repository_file(root, relative, *, existing=True):
    path = root
    for part in relative_path(relative).parts:
        path /= part
        require(not path.is_symlink(), "symlink repository descendant")
    require(path.resolve().is_relative_to(root), "outside repository path")
    if existing:
        require(path.is_file(), "selected input missing or not a regular file")
    elif path.exists():
        require(path.is_file(), "output is not a regular file")
    return path


def valid_digest(value):
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def metadata_pin(item):
    """Validate a metadata descriptor without resolving or accessing its target."""
    require(isinstance(item, dict), "input descriptor required")
    relative_path(item.get("path"))
    require(type(item.get("bytes")) is int and item["bytes"] >= 0
            and valid_digest(item.get("sha256")), "invalid byte identity")


def pinned(root, item, fingerprints):
    metadata_pin(item)
    require(item["bytes"] <= MAX_INPUT_BYTES, "selected input too large")
    require(item["path"] not in fingerprints, "duplicate selected input")
    with repository_file(root, item["path"]).open("rb") as stream:
        body = stream.read(MAX_INPUT_BYTES + 1)
    require(len(body) == item["bytes"] and sha(body) == item["sha256"], "selected input identity changed")
    fingerprints[item["path"]] = {"bytes": len(body), "sha256": sha(body)}
    return body


def validate_legacy_disposition(document, legacy):
    require(isinstance(document, dict)
            and document.get("schema") == "ktchecker/legacy-coverage-evidence-disposition/1"
            and document.get("legacy_selection_sha256") == LEGACY_SELECTION_PIN["sha256"]
            and document.get("disposition") == "unavailable_in_declared_only_successor",
            "invalid legacy disposition")
    require(document.get("all_catalog_context_obligations_retained") is True
            and all(document.get(key) is False for key in (
                "current_evidence_selected", "stage_transfer_allowed", "scope_reduced", "rule_disabling_authorized")),
            "legacy disposition cannot transfer credit or reduce scope")
    for group in ("selected_evidence", "reference_files", "mappings"):
        require(document.get(group) == legacy.get(group) and isinstance(document[group], list),
                "legacy metadata identities changed")
        for item in document[group]:
            metadata_pin(item)  # Deliberately NOT pinned(): the target must remain unread.
    denied = document.get("denied_reference_paths")
    require(isinstance(denied, list) and len(denied) == len(set(denied))
            and set(denied) == DENIED_REFERENCE_PATHS
            and set(denied) <= {item["path"] for item in legacy["reference_files"]},
            "invalid denied-reference metadata")


def validate_historical_reference(document, legacy):
    require(isinstance(document, dict)
            and document.get("schema") == "ktchecker/historical-inventory-reference/1"
            and document.get("status") == "source_reference_unavailable", "invalid historical reference")
    relative_path(document.get("report_path"))  # No filesystem operation on the report.
    require(valid_digest(document.get("report_sha256")) and document.get("source_sha256") is None
            and document.get("implemented_cell_identities") is None
            and document.get("current_counts") is None
            and all(document.get(key) is False for key in (
                "source_binding_verified", "historical_report_read", "current_stage_transfer_allowed")),
            "unknown historical binding cannot be promoted")
    scope = {key: legacy["scope"][key] for key in ("catalog_records", "contexts", "rows")}
    require(document.get("historical_scope") == scope, "historical scope changed")
    claims = document.get("historical_claims")
    require(isinstance(claims, dict) and set(claims) == {"mapped", *STAGES, "runtime_defaults_enabled"}
            and all(type(claims[key]) is int and 0 <= claims[key] <= scope["rows"]
                    for key in ("mapped", *STAGES))
            and claims["runtime_defaults_enabled"] is False, "invalid historical claims")


def validate_withdrawals(document, legacy, records, contexts):
    require(isinstance(document, dict)
            and document.get("schema") == "ktchecker/repository-coverage-withdrawals/1",
            "invalid withdrawal metadata")
    expected = legacy["scope"]["withdrawn_provider_rule_context_cells"]
    cells = document.get("cells")
    require(isinstance(cells, list) and len(cells) == expected
            == document.get("withdrawn_provider_rule_context_cells")
            == EXPECTED_SCOPE["withdrawn_provider_rule_context_cells"], "withdrawal obligations changed")
    historical = set()
    require(isinstance(document.get("historical_envelopes"), list), "historical envelope metadata required")
    for envelope in document["historical_envelopes"]:
        require(isinstance(envelope, dict) and envelope.get("selected_for_current_evidence") is False,
                "withdrawn envelope cannot be reselected")
        path = relative_path(envelope.get("repository_path")).as_posix()
        for name in ("original_archive", "integration_variant_at_selection"):
            item = envelope.get(name)
            require(isinstance(item, dict), "historical identity required")
            metadata_pin({**item, "path": path})
            historical.add((path, item["sha256"]))
    identities = set()
    for cell in cells:
        require(isinstance(cell, dict), "withdrawal cell required")
        identity = tuple(cell.get(key) for key in ("rule_id", "context_sha256", "provider_id", "provider_version"))
        require(all(isinstance(value, str) and value for value in identity) and identity not in identities,
                "invalid or duplicate withdrawal identity")
        require(identity[0] in records and identity[1] in contexts
                and cell.get("context_id") == contexts[identity[1]]["id"]
                and (cell.get("sidecar_path"), cell.get("sidecar_sha256")) in historical,
                "withdrawal identity outside retained scope")
        require(cell.get("all_catalog_rule_rows_and_complete_context_retained") is True
                and all(cell.get(key) is False for key in (
                    "current_evidence_selected", "current_implemented", "current_tested", "current_qualified",
                    "stage_transfer_allowed", "scope_reduced", "disabled")),
                "withdrawal cannot transfer stages or disable obligations")
        identities.add(identity)


def checked_inputs(root):
    fingerprints = {}
    path = repository_file(root, SELECTION)
    with path.open("rb") as stream:
        body = stream.read(1024 * 1024 + 1)
    require(len(body) <= 1024 * 1024, "selector too large")
    fingerprints[SELECTION] = {"bytes": len(body), "sha256": sha(body)}
    selection = decode(body)
    require(isinstance(selection, dict)
            and set(selection) == {"schema", "contract_id", "scope", "catalogs", "contexts", "legacy_selection",
                                   "selected_evidence", "selected_reference_files", "selection_note", *FIXED_FILES}
            and selection.get("schema") == "ktchecker/declared-coverage-ledger-selection/1"
            and selection.get("contract_id") == CONTRACT_ID, "unsupported declared-ledger selector")
    require(selection.get("scope") == EXPECTED_SCOPE
            and all(type(value) is int for value in selection["scope"].values()), "declared scope changed")
    require(selection.get("selected_evidence") == [] and selection.get("selected_reference_files") == [],
            "declared-only ledger cannot select execution artifacts")
    require(selection.get("legacy_selection") == LEGACY_SELECTION_PIN, "frozen legacy selector changed")
    legacy = decode(pinned(root, selection["legacy_selection"], fingerprints))
    require(isinstance(legacy, dict) and legacy.get("schema") == "ktchecker/current-coverage-selection/1",
            "invalid frozen selector metadata")
    documents = {}
    for name, relative in FIXED_FILES.items():
        item = selection[name]
        require(isinstance(item, dict) and item.get("path") == relative, "fixed bookkeeping input required")
        raw = pinned(root, item, fingerprints)
        documents[name] = raw if name in ("reporter", "runner") else decode(raw)
    require(selection["withdrawals"] == legacy["withdrawals"]
            and selection["reporter"] == legacy["reporter"], "frozen withdrawal/reporter identity changed")
    catalogs = selection.get("catalogs")
    require(isinstance(catalogs, list) and catalogs
            and {item["path"] for item in catalogs} == {item["path"] for item in legacy["catalogs"]}
            == {p.relative_to(root).as_posix() for p in (root / "data").glob("rules-*.json")},
            "explicit catalog file set changed")
    records = {}
    for item in catalogs:
        document = decode(pinned(root, item, fingerprints))
        require(isinstance(document, list), "catalog array required")
        for record in document:
            require(isinstance(record, dict) and isinstance(record.get("id"), str) and record["id"]
                    and record["id"] not in records, "invalid or duplicate catalog record")
            records[record["id"]] = {"rule_id": record["id"], "catalog_path": item["path"],
                                      "catalog_record_sha256": sha(canonical(record)),
                                      "default_activation": record.get("default_activation", "unknown")}
    require(len(records) == EXPECTED_SCOPE["catalog_records"] and sha(canonical(sorted(records))) == IDS_SHA256,
            "owner full-catalog identity changed; legacy or partial counts are not current")
    baseline = documents["baseline"]
    require(isinstance(baseline, dict) and baseline.get("schema") == "ktchecker/catalog-record-baseline/1"
            and baseline.get("contract_id") == CONTRACT_ID and baseline.get("catalog_records") == len(records)
            and baseline.get("ids_sha256") == IDS_SHA256
            and baseline.get("records") == [records[key] for key in sorted(records)]
            and baseline.get("added_rule_ids") == sorted(ADDED_RULE_IDS), "catalog baseline changed")
    predecessor = sorted(set(records) - ADDED_RULE_IDS)
    previous = baseline.get("predecessor")
    require(isinstance(previous, dict) and len(predecessor) == legacy["scope"]["catalog_records"]
            == previous.get("catalog_records")
            and sha(canonical(predecessor)) == PREDECESSOR_IDS_SHA256 == previous.get("ids_sha256")
            and previous.get("record_identities_sha256")
            == sha(canonical([[key, records[key]["catalog_record_sha256"]] for key in predecessor]))
            == PREDECESSOR_RECORDS_SHA256,
            "predecessor catalog identities not retained exactly")
    require(selection.get("contexts") == legacy["contexts"], "entire frozen context selection changed")
    contexts = {}
    for item in selection["contexts"]:
        context = decode(pinned(root, item, fingerprints))
        require(isinstance(context, dict) and context.get("id") == item.get("context_id"), "context ID changed")
        identity = sha(canonical(context))
        require(identity == item.get("context_sha256") and identity not in contexts, "duplicate or changed entire context")
        contexts[identity] = context
    require(len(contexts) == EXPECTED_SCOPE["contexts"]
            and len(contexts) * len(records) == EXPECTED_SCOPE["rows"], "complete Cartesian scope changed")
    validate_legacy_disposition(documents["legacy_disposition"], legacy)
    validate_historical_reference(documents["historical_inventory_reference"], legacy)
    validate_withdrawals(documents["withdrawals"], legacy, records, contexts)
    return {"selection": selection, "fingerprints": fingerprints, "records": records,
            "contexts": contexts, "documents": documents}


def load_visibility_reporter(root, source):
    module = types.ModuleType("_ktchecker_declared_visibility")
    module.__file__ = str(root / FIXED_FILES["reporter"])
    exec(compile(source, module.__file__, "exec"), module.__dict__)
    return module


def reconcile_visibility(visible, checked):
    records, contexts = checked["records"], checked["contexts"]
    require(isinstance(visible, dict) and visible.get("kind") == "ktchecker_declared_catalog_visibility"
            and visible.get("status") == "declared_inventory_evidence_unavailable"
            and visible.get("catalog_valid") is True and visible.get("context_scope_known") is True
            and visible.get("context_scope_source") == "explicit"
            and all(visible.get(key) is False for key in (
                "evidence_valid", "current_coverage_known", "all_requested_cells_qualified", "release_qualified")),
            "visibility is not a declared-only unavailable projection")
    require(visible.get("catalog_records") == len(records) and visible.get("context_count") == len(contexts)
            and {sha(canonical(context)): context for context in visible.get("contexts", [])} == contexts
            and len(visible["contexts"]) == len(contexts), "visibility scope changed")
    declared = visible.get("catalog_rules")
    require(isinstance(declared, list) and len(declared) == len(records), "missing declared catalog records")
    seen = set()
    for rule in declared:
        require(isinstance(rule, dict) and rule.get("rule_id") in records
                and rule["rule_id"] not in seen, "duplicate or unknown declared rule")
        require(all(rule.get(key) == value for key, value in records[rule["rule_id"]].items()),
                "declared catalog metadata changed")
        seen.add(rule["rule_id"])
    rows = visible.get("rows")
    require(isinstance(rows, list) and len(rows) == visible.get("row_count") == len(records) * len(contexts),
            "missing Cartesian obligations")
    identities = set()
    for row in rows:
        require(isinstance(row, dict), "invalid declared row")
        key = (row.get("rule_id"), row.get("context_sha256"))
        require(key[0] in records and key[1] in contexts and key not in identities, "duplicate or extra declared identity")
        require(row.get("catalog_record_sha256") == records[key[0]]["catalog_record_sha256"]
                and row.get("mapped") is None and row.get("evidence_status") == "unavailable"
                and row.get("evaluation_state") == "incomplete"
                and row.get("mapping_reference_error_indexes") == []
                and row.get("state") == ("excluded" if records[key[0]]["default_activation"] == "excluded"
                                         else "current_evidence_unavailable")
                and not {"cases", "implementation_evidence", "qualification_evidence", "providers"}.intersection(row)
                and all(row.get(stage) is False for stage in (*STAGES, "runtime_default_enabled")),
                "declared row cannot grant mapping, stages or activation")
        identities.add(key)
    excluded = sum(record["default_activation"] == "excluded" for record in records.values())
    require((visible.get("excluded_records"), visible.get("candidate_records"),
             visible.get("excluded_cells"), visible.get("candidate_cells"))
            == (excluded, len(records) - excluded, excluded * len(contexts), (len(records) - excluded) * len(contexts)),
            "catalog disposition arithmetic changed")


def unavailable(status):
    return {"schema": "ktchecker/declared-coverage-ledger/1", "kind": "ktchecker_declared_coverage_ledger",
            "contract_id": CONTRACT_ID, "ledger_status": status, "ledger_valid": False,
            "inventory_status": "unavailable", "inventory_valid": False, "current_coverage_known": False,
            "catalog_records": None, "contexts": None, "row_count": None, "rows": None,
            "summary": None, "states": None, "catalog_visibility": None,
            "all_requested_cells_qualified": False, "release_qualified": False,
            "gaps": ["declared_ledger_" + status, "current_evidence_not_selected_or_evaluated"]}


def generate_ledger(root, checked):
    reporter = load_visibility_reporter(root, checked["documents"]["reporter"])
    # Explicit nonempty contexts and empty evidence: no default/observed scope and
    # no strict generator, mapping-reference scan, archive discovery or case reads.
    visible = reporter.catalog_visibility(root, evidence_paths=[], contexts=list(checked["contexts"].values()))
    reconcile_visibility(visible, checked)
    for row in visible["rows"]:
        row["catalog_state"] = "excluded" if checked["records"][row["rule_id"]]["default_activation"] == "excluded" else "included"
        row["state"] = "current_evidence_unavailable"
        row["obligation_retained"] = True
        row["scope_reduced"] = False
        row["rule_disabled_by_this_ledger"] = False
    visible["scope"] = "All catalog/context obligations, including catalog exclusions, remain visible. Current stages are unevaluated, not measured zero."
    visible["gaps"] = ["current_evidence_selection_unavailable", "legacy_artifacts_not_read", "no_qualification_or_runtime_activation"]
    output = unavailable("declared_scope_verified_current_evidence_unavailable")
    output.update({"ledger_valid": True, "catalog_visibility": visible,
                   "declared_scope": dict(EXPECTED_SCOPE),
                   "selected_evidence": [], "selected_reference_files": [],
                   "legacy_withdrawals": checked["documents"]["withdrawals"],
                   "legacy_evidence_disposition": checked["documents"]["legacy_disposition"],
                   "historical_inventory_reference": checked["documents"]["historical_inventory_reference"],
                   "source_binding": {"kind": "selected_source_and_bookkeeping_metadata_only",
                                      "whole_worktree_sha256": None, "inputs": checked["fingerprints"]},
                   "gaps": ["current_evidence_selection_unavailable", "historical_source_reference_unavailable",
                            "historical_claims_not_transferred", "declared_ledger_not_execution_or_release_qualification"]})
    return output


def publish(root, relative, value):
    path = repository_file(root, relative, existing=False)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="wb", prefix=".ktchecker-declared-coverage-", suffix=".tmp",
                                         dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            size = 0
            encoder = json.JSONEncoder(sort_keys=True, ensure_ascii=False, separators=(",", ":"))
            for chunk in encoder.iterencode(value):
                body = chunk.encode()
                size += len(body)
                require(size + 1 <= MAX_OUTPUT_BYTES, "serialized ledger output size limit exceeded")
                stream.write(body)
            stream.write(b"\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default=DEFAULT_OUTPUT,
                        help="JSON below " + OUTPUT_DIRECTORY + "; never a legacy inventory path.")
    args = parser.parse_args(argv)
    output_safe = False
    try:
        root = Path(__file__).resolve(strict=True).parents[1]
        output = relative_path(args.output)
        require(output.is_relative_to(PurePosixPath(OUTPUT_DIRECTORY)) and output.suffix == ".json"
                and len(output.parts) > len(PurePosixPath(OUTPUT_DIRECTORY).parts), "output must use the dedicated ledger directory")
        repository_file(root, args.output, existing=False)
        output_safe = True
        publish(root, args.output, unavailable("pending"))
        before = checked_inputs(root)
        ledger = generate_ledger(root, before)
        after = checked_inputs(root)
        require(before["fingerprints"] == after["fingerprints"], "selected inputs changed during ledger generation")
        publish(root, args.output, ledger)
        return 0
    except Exception:
        if output_safe:
            try:
                publish(root, args.output, unavailable("unavailable"))
            except Exception:
                print("declared ledger error: invalid publication failed; do not use an existing output", file=sys.stderr)
        print("declared ledger error: unavailable; no current evidence or qualification established", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

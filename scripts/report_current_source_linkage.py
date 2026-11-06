#!/usr/bin/env python3
"""Read one explicitly pinned current SOURCE linkage selection; never run a provider.

source_implementation_linked means selected registry/normalizer source references
match their bytes. It does NOT mean that every mapped diagnostic is implemented,
emitted, integrated, precise, or qualified. route_linked names a source route and
its exact mapping compatibility, not an executed/passed route. Evidence stages
remain unknown in this source-only version; generic suite totals, historical
checkpoints and authored fixtures are not accepted as per-rule execution receipts.

A public derived receipt pins exactly 254 selected source files. Its HEAD/dirty
identity and original snapshot digest/size/count/aggregate are historical owner
assertions, not a current whole-tree claim. The original snapshot is not opened;
only selected source bytes and their membership in the derived subset are checked.
No subset entry or legacy reference is followed automatically.
Inputs must be quiescent ordinary files. This cooperative reader is not a defence
against an adversarial same-UID writer. Output is JSON on stdout only; no files,
directories, imports of candidate code, subprocesses, or network are created.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import stat
import sys

MAX_FILE = 8_388_608
MAX_TOTAL = 32 * 1024 * 1024
MAX_FILES = 512
MAX_OUTPUT = 16 * 1024 * 1024
CASE_KINDS = ("positive", "negative", "configuration", "version", "suppression", "regression")
STAGES = ("implemented", "tested", "qualified")
RELATIONS = ("exact", "requires_refinement", "excluded_alias")


class LinkageError(ValueError):
    """A stable error code, never an input's private content."""


def need(condition, code):
    if not condition:
        raise LinkageError(code)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
                      allow_nan=False).encode("utf-8")


def pairs(items):
    result = {}
    for key, value in items:
        need(key not in result, "duplicate_json_key")
        result[key] = value
    return result


def invalid_constant(_value):
    raise LinkageError("nonfinite_json_number")


def document(data):
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=pairs,
                          parse_constant=invalid_constant)
    except (UnicodeError, ValueError, RecursionError) as error:
        if isinstance(error, LinkageError):
            raise
        raise LinkageError("invalid_json") from error


def name(value):
    need(isinstance(value, str) and 0 < len(value) <= 4096 and
         not any(c in value for c in ("\\", "\x00", "\r", "\n")), "unsafe_path")
    need(not PurePosixPath(value).is_absolute() and not PureWindowsPath(value).drive and
         all(p not in ("", ".", "..") for p in value.split("/")), "unsafe_path")
    return value


def digest_value(value, length=64):
    need(isinstance(value, str) and re.fullmatch("[0-9a-f]{" + str(length) + "}", value),
         "invalid_digest")
    return value


def pin(value):
    need(isinstance(value, dict), "invalid_reference")
    result = {"path": name(value.get("path")), "sha256": digest_value(value.get("sha256")),
              "bytes": value.get("bytes")}
    need(type(result["bytes"]) is int and 0 <= result["bytes"] <= MAX_FILE, "invalid_reference_size")
    return result


class Reader:
    def __init__(self, root):
        root = Path(root)
        need(root.is_absolute(), "absolute_input_root_required")
        self.root = root.resolve(strict=True)  # Only the explicitly selected outer root may be an alias.
        need(self.root.is_dir(), "input_root_not_directory")
        self.files = {}
        self.total = 0

    def _read(self, reference):
        path = self.root
        parts = name(reference["path"]).split("/")
        for index, part in enumerate(parts):
            path /= part
            mode = path.lstat().st_mode
            need(not stat.S_ISLNK(mode), "symlink_input")
            need(stat.S_ISREG(mode) if index == len(parts) - 1 else stat.S_ISDIR(mode),
                 "nonordinary_input")
        before = path.stat()
        need(before.st_size == reference["bytes"] and before.st_size <= MAX_FILE, "input_size_mismatch")
        with path.open("rb") as stream:
            data = stream.read(MAX_FILE + 1)
        after = path.lstat()
        identity = lambda st: (st.st_dev, st.st_ino, st.st_mode, st.st_size, st.st_mtime_ns)
        need(identity(before) == identity(after), "input_changed_during_read")
        need(len(data) == reference["bytes"] and sha(data) == reference["sha256"], "input_digest_mismatch")
        return data

    def read(self, value):
        reference = pin(value)
        previous = self.files.get(reference["path"])
        if previous is not None:
            need(previous[0] == reference, "conflicting_reference")
            return previous[1]
        need(len(self.files) < MAX_FILES and self.total + reference["bytes"] <= MAX_TOTAL,
             "selected_input_budget_exceeded")
        data = self._read(reference)
        self.files[reference["path"]] = (reference, data)
        self.total += len(data)
        return data

    def json(self, reference):
        return document(self.read(reference))

    def recheck(self):
        for reference, _ in self.files.values():
            self._read(reference)


def load_declared(reader, selection):
    declared = reader.json(selection["declared_selection"])
    need(declared.get("schema") == "ktchecker/declared-coverage-ledger-selection/1", "declared_schema")
    need(declared.get("selected_evidence") == [] and declared.get("selected_reference_files") == [],
         "declaration_is_not_evidence_selection")
    expected = selection["expected_scope"]
    need(isinstance(expected, dict) and set(expected) == {"catalog_records", "contexts", "rows"}, "scope_schema")
    need(all(type(v) is int and v > 0 for v in expected.values()), "scope_values")
    need(all(declared["scope"].get(k) == v for k, v in expected.items()), "declared_scope_mismatch")
    baseline = reader.json(declared["baseline"])
    need(baseline.get("schema") == "ktchecker/catalog-record-baseline/1" and
         baseline.get("contract_id") == declared.get("contract_id"), "baseline_contract")
    catalog = {}
    catalog_paths = set()
    for reference in declared["catalogs"]:
        relative = pin(reference)["path"]
        need(relative not in catalog_paths, "duplicate_catalog_selection")
        catalog_paths.add(relative)
        records = reader.json(reference)
        need(isinstance(records, list), "catalog_array_required")
        for record in records:
            need(isinstance(record, dict) and isinstance(record.get("id"), str) and
                 record["id"].startswith("ktc."), "catalog_rule_id")
            rule_id = record["id"]
            need(rule_id not in catalog, "duplicate_catalog_rule")
            catalog[rule_id] = {"rule_id": rule_id, "catalog_path": relative,
                                "catalog_record_sha256": sha(canonical(record)),
                                "default_activation": record.get("default_activation", "unknown")}
    baseline_rows = baseline.get("records")
    need(isinstance(baseline_rows, list) and len(baseline_rows) == len(catalog) and
         all(isinstance(r, dict) for r in baseline_rows), "baseline_rows")
    by_id = {r.get("rule_id"): r for r in baseline_rows}
    need(len(by_id) == len(baseline_rows) and by_id == catalog, "complete_baseline_identity_mismatch")
    need(len(catalog) == expected["catalog_records"] == baseline.get("catalog_records") and
         sha(canonical(sorted(catalog))) == baseline.get("ids_sha256"), "catalog_denominator_mismatch")
    contexts = {}
    context_ids = set()
    for reference in declared["contexts"]:
        context = reader.json(reference)
        need(isinstance(context, dict) and isinstance(context.get("id"), str), "context_object")
        key = sha(canonical(context))
        need(key == reference.get("context_sha256") and context["id"] == reference.get("context_id"),
             "whole_context_identity_mismatch")
        need(key not in contexts and context["id"] not in context_ids, "duplicate_context")
        contexts[key] = context
        context_ids.add(context["id"])
    need(len(contexts) == expected["contexts"] and len(contexts) * len(catalog) == expected["rows"],
         "context_denominator_mismatch")
    # Deliberately never follow declared legacy_selection, withdrawals, historical
    # inventory, runner, reporter, legacy_disposition or their reference targets.
    return declared, catalog, contexts


def load_mappings(reader, selection, catalog):
    by_rule = {rule_id: [] for rule_id in catalog}
    by_path = {}
    seen = set()
    need(isinstance(selection["mapping_files"], list) and selection["mapping_files"], "mapping_selection_required")
    for reference in selection["mapping_files"]:
        path = pin(reference)["path"]
        need(path not in by_path, "duplicate_mapping_selection")
        mapping = reader.json(reference)
        need(mapping.get("schema_version") == 1 and isinstance(mapping.get("mappings"), list), "mapping_schema")
        provider, version = mapping.get("provider_id"), mapping.get("provider_version")
        need(isinstance(provider, str) and provider and isinstance(version, str) and version, "mapping_provider")
        by_path[path] = (pin(reference), mapping)
        for row in mapping["mappings"]:
            need(isinstance(row, dict) and row.get("rule_id") in catalog, "unknown_mapped_rule")
            identity = (provider, version, row["rule_id"])
            need(identity not in seen, "duplicate_provider_rule")
            seen.add(identity)
            need(row.get("relation") in RELATIONS, "mapping_relation")
            record_pin = row.get("catalog_record_sha256")
            if record_pin is not None:
                need(record_pin == catalog[row["rule_id"]]["catalog_record_sha256"], "mapping_catalog_record_drift")
            by_rule[row["rule_id"]].append({"provider_id": provider, "provider_version": version,
                "mapping_path": path, "upstream_id": row.get("upstream_id"), "relation": row["relation"],
                "catalog_record_pin_verified": True if record_pin is not None else None,
                "backend": row.get("backend"), "source_implementation_linked": None})
            # Other providers' implementation_evidence entries are unselected
            # metadata, not permission to read those paths (including binary refs).
    return by_rule, by_path


def references(reader, values):
    need(isinstance(values, list), "source_reference_array")
    result = {}
    for value in values:
        reference = pin(value)
        need(reference["path"] not in result, "duplicate_source_reference")
        reader.read(reference)
        result[reference["path"]] = reference
    return result


def require_text(reader, reference, fragments):
    try:
        text = reader.read(reference).decode("utf-8")
    except UnicodeError as error:
        raise LinkageError("source_not_utf8") from error
    need(all(fragment in text for fragment in fragments), "reviewed_source_guard_changed")


def compiler_linkage(reader, selection, by_rule, by_path):
    spec = selection["compiler_slice"]
    path = name(spec["mapping_path"])
    need(path in by_path, "compiler_mapping_not_selected")
    mapping_ref, mapping = by_path[path]
    need((mapping["provider_id"], mapping["provider_version"], mapping.get("mapping_contract")) ==
         (spec["provider_id"], spec["provider_version"], spec["mapping_contract"]), "compiler_identity_mismatch")
    rows = mapping["mappings"]
    mapped = {row["rule_id"]: row for row in rows}
    need(len(rows) == spec["mapping_rows"] and
         sum(r.get("backend") in ("common", "jvm", "ir") for r in rows) == spec["jvm_applicable_mapping_rows"] and
         sum(r.get("backend") == "native" for r in rows) == spec["native_mapping_rows"], "compiler_backend_denominator")
    sources = references(reader, spec["implementation_sources"])
    required = {}
    for row in rows:
        need(isinstance(row.get("implementation_evidence"), list) and row["implementation_evidence"],
             "compiler_source_references_missing")
        for reference in row["implementation_evidence"]:
            source_path = name(reference.get("path"))
            expected = digest_value(reference.get("sha256"))
            need(source_path in sources and sources[source_path]["sha256"] == expected, "compiler_source_pin_mismatch")
            required[source_path] = expected
        projected = next(r for r in by_rule[row["rule_id"]] if r["mapping_path"] == path)
        projected["source_implementation_linked"] = row["relation"] == "exact" and row.get("backend") in ("common", "jvm", "ir")
    need(set(required) == set(sources), "compiler_source_closure_mismatch")
    registry = spec["registry_source"]
    need(pin(registry)["path"] in sources and pin(registry) == sources[pin(registry)["path"]], "registry_not_in_source_closure")
    require_text(reader, registry, [f'const val MAPPING_CONTRACT = "{spec["mapping_contract"]}"',
        'val jvmApplicable: Boolean get() = backend in setOf("common", "jvm", "ir")'])

    ordinary = spec["ordinary_pairs"]
    roster = reader.json(ordinary["manifest"])
    need(roster.get("schema_version") == 1 and roster.get("kotlin_version") == spec["provider_version"], "ordinary_roster_schema")
    fixtures = roster.get("fixtures")
    need(isinstance(fixtures, list) and len(fixtures) == ordinary["pair_count"], "ordinary_pair_denominator")
    case_sources = references(reader, ordinary["case_sources"])
    authored = {}
    used = set()
    for fixture in fixtures:
        rule_id = fixture.get("rule_id")
        need(rule_id in mapped and rule_id not in authored and
             fixture.get("upstream_id") == mapped[rule_id]["upstream_id"], "ordinary_rule_join")
        cases = []
        for kind in ("positive", "negative"):
            relative = "providers/compiler-fixtures/" + name(fixture.get(kind))
            need(relative in case_sources, "ordinary_case_not_selected")
            cases.append({"kind": kind, "source": case_sources[relative]})
            used.add(relative)
        authored[rule_id] = {"ordinary_cases": cases, "authored_status_not_execution": fixture.get("status")}
    need(used == set(case_sources), "ordinary_case_closure_mismatch")
    legacy_contract = ordinary["required_mapping_contract"]
    legacy_sha = digest_value(ordinary["required_mapping_sha256"])
    require_text(reader, ordinary["runner_source"],
                 ["CompilerFixtureMappingContract.requireLegacy(CompilerRuleRegistry.load().mappingIdentity)"])
    require_text(reader, ordinary["mapping_contract_source"],
                 [f'CompilerMappingIdentity("{legacy_contract}"', '"sha256:' + legacy_sha + '"',
                  "fun requireLegacy(actual: CompilerMappingIdentity) = requireExact(LEGACY443, actual)"])
    compatible = (legacy_contract, legacy_sha) == (spec["mapping_contract"], mapping_ref["sha256"])
    ordinary_route = {"route_id": "compiler_legacy443_ordinary_pairs", "source_route_linked": True,
        "compatible_with_selected_mapping": compatible, "ordinary_six_kind_source_route": True,
        "required_mapping_contract": legacy_contract, "required_mapping_sha256": legacy_sha,
        "selected_mapping_contract": spec["mapping_contract"], "selected_mapping_sha256": mapping_ref["sha256"],
        "rule_ids": sorted(authored), "runner_source": pin(ordinary["runner_source"]),
        "contract_source": pin(ordinary["mapping_contract_source"]), "execution_selected": False}

    obs = spec["current_observations"]
    plan, binding = reader.json(obs["source_plan"]), reader.json(obs["execution_binding"])
    need(plan.get("ordinary_six_kind_assessment") is False and plan.get("qualification") == "none" and
         plan.get("ordered_cases_are_execution_authority") is False, "observation_plan_is_not_coverage")
    need(binding.get("mapping_contract") == spec["mapping_contract"] and binding.get("mapping_sha256") == mapping_ref["sha256"] and
         binding.get("source_plan_sha256") == pin(obs["source_plan"])["sha256"], "observation_mapping_binding")
    cases = plan.get("cases")
    need(isinstance(cases, list) and len(cases) == obs["case_count"], "observation_case_denominator")
    case_ids = [case.get("case_id") for case in cases]
    need(all(isinstance(case_id, str) and case_id for case_id in case_ids) and len(set(case_ids)) == len(case_ids) and
         case_ids == binding.get("ordered_case_ids"), "observation_case_identity")
    selected = binding.get("selected_rule_ids")
    need(isinstance(selected, list) and selected and len(set(selected)) == len(selected) and
         all(rule_id in mapped for rule_id in selected), "observation_rule_selection")
    obs_sources = references(reader, obs["case_sources"])
    used = set()
    for case in cases:
        relative = name(case.get("path"))
        need(relative in obs_sources and obs_sources[relative]["sha256"] == case.get("source_sha256") and
             obs_sources[relative]["bytes"] == case.get("source_bytes"), "observation_source_pin")
        need(case.get("selected_rule_ids") == selected and
             case.get("counts_as_current_test_or_implementation_evidence") is False and
             case.get("expected_outcome_matched") is None, "observation_authorship_not_execution")
        expectations = case.get("source_derived_expectations")
        need(isinstance(expectations, list) and len(expectations) == len(selected) and
             {e.get("stable_id") for e in expectations} == set(selected), "observation_expectation_roster")
        need(all(e.get("upstream_id") == mapped[e["stable_id"]]["upstream_id"] for e in expectations), "observation_upstream_join")
        used.add(relative)
    need(used == set(obs_sources), "observation_source_closure_mismatch")
    require_text(reader, obs["runner_source"], ['"ordinary_six_kind_assessment" to false',
        '"counts_as_current_test_or_implementation_evidence" to false', '"run_complete" to false'])
    require_text(reader, obs["integration_test_source"], ["assertFalse(receipt.runComplete)",
        "assertFalse(receipt.sourcePlanRawByteRetentionCapabilityResolved)"])
    observation_route = {"route_id": binding["execution_binding_id"], "source_route_linked": True,
        "compatible_with_selected_mapping": True, "ordinary_six_kind_source_route": False,
        "rule_ids": sorted(selected), "case_ids": case_ids, "source_plan": pin(obs["source_plan"]),
        "execution_binding": pin(obs["execution_binding"]), "runner_source": pin(obs["runner_source"]),
        "integration_test_source": pin(obs["integration_test_source"]), "execution_selected": False,
        "coverage_stage_credit": False, "unknown_expectations_preserved": True}
    return authored, [ordinary_route, observation_route], len(sources)


def check_selected_subset(reader, selection, selection_ref):
    reference = pin(selection["selected_source_subset"])
    subset = reader.json(reference)
    # Exact derived-receipt schema, not a fallback parser for an ignored build
    # snapshot. The source selection and this receipt are exempt from its roster.
    subset_keys = {"schema", "head", "dirty", "selected_source_file_count",
                   "selected_source_bytes", "owner_original_snapshot_assertion", "files"}
    need(isinstance(subset, dict) and set(subset) == subset_keys and
         subset["schema"] == "ktchecker/derived-selected-source-subset/1",
         "selected_source_subset_schema")
    need(subset["head"] == digest_value(selection["expected_source_commit"], 40),
         "selected_source_subset_identity")
    need(type(selection["expected_source_dirty"]) is bool and type(subset["dirty"]) is bool and
         subset["dirty"] is selection["expected_source_dirty"], "selected_source_subset_dirty_identity")

    original = subset["owner_original_snapshot_assertion"]
    expected_original = selection["expected_owner_original_snapshot_assertion"]
    original_keys = {"sha256", "bytes", "file_count", "aggregate_sha256"}
    need(isinstance(original, dict) and set(original) == original_keys and
         isinstance(expected_original, dict) and set(expected_original) == original_keys,
         "owner_original_snapshot_assertion_schema")
    for metadata in (original, expected_original):
        need(type(metadata["bytes"]) is int and 0 < metadata["bytes"] <= MAX_FILE and
             type(metadata["file_count"]) is int and 0 < metadata["file_count"] <= 100_000 and
             isinstance(metadata["sha256"], str) and re.fullmatch("[0-9a-f]{64}", metadata["sha256"]) and
             isinstance(metadata["aggregate_sha256"], str) and
             re.fullmatch("[0-9a-f]{64}", metadata["aggregate_sha256"]),
             "owner_original_snapshot_assertion_identity")
    need(original == expected_original, "owner_original_snapshot_assertion_identity")
    # These four values are only pinned OWNER metadata. There is deliberately no
    # original-snapshot path and no read, whole-tree hash or 4,788-file verification.
    count = selection["expected_selected_source_file_count"]
    total = selection["expected_selected_source_bytes"]
    need(type(count) is int and count == 254 and
         type(subset["selected_source_file_count"]) is int and
         subset["selected_source_file_count"] == count, "selected_source_subset_file_count")
    need(type(total) is int and 0 < total <= MAX_TOTAL and
         type(subset["selected_source_bytes"]) is int and
         subset["selected_source_bytes"] == total, "selected_source_subset_byte_count")
    entries = subset["files"]
    need(isinstance(entries, list) and len(entries) == count, "selected_source_subset_file_roster")
    by_path = {}
    for entry in entries:
        need(isinstance(entry, dict) and set(entry) == {"path", "bytes", "sha256"},
             "selected_source_subset_file_entry")
        selected = pin(entry)
        need(selected["path"] not in by_path, "selected_source_subset_duplicate_path")
        by_path[selected["path"]] = selected
    need(list(by_path) == sorted(by_path), "selected_source_subset_file_order")
    exempt = {reference["path"], selection_ref["path"]}
    selected_by_path = {selected["path"]: selected for selected, _ in reader.files.values()
                        if selected["path"] not in exempt}
    need(len(selected_by_path) == count and set(by_path) == set(selected_by_path),
         "selected_source_subset_membership")
    need(by_path == selected_by_path, "selected_source_subset_pin_mismatch")
    need(sum(entry["bytes"] for entry in entries) == total, "selected_source_subset_byte_count")
    return {"selected_subset_reference": reference, "selected_subset_schema": subset["schema"],
        "snapshot_head_commit": subset["head"], "snapshot_dirty": subset["dirty"],
        "snapshot_identity_is_historical_owner_assertion": True,
        "selected_subset_file_count": len(entries), "selected_subset_bytes": total,
        "selected_subset_matches": True,
        "owner_original_snapshot_sha256": original["sha256"],
        "owner_original_snapshot_bytes": original["bytes"],
        "owner_original_snapshot_file_count": original["file_count"],
        "owner_original_snapshot_aggregate_sha256": original["aggregate_sha256"],
        "original_snapshot_metadata_is_historical_owner_assertion": True,
        "original_snapshot_bytes_verified_here": False, "whole_tree_recomputed": False,
        "snapshot_aggregate_is_current_tree_claim": False, "runtime_execution_credit": False}


def create_report(reader, selection_ref):
    selection_ref = pin(selection_ref)
    selection = reader.json(selection_ref)
    selection_keys = {"schema", "scope_note", "compiler_slice", "declared_selection", "expected_scope",
                      "expected_source_commit", "expected_source_dirty", "mapping_files",
                      "selected_current_rule_evidence", "selected_source_subset",
                      "expected_owner_original_snapshot_assertion", "expected_selected_source_file_count",
                      "expected_selected_source_bytes"}
    need(isinstance(selection, dict) and set(selection) == selection_keys and
         selection["schema"] == "ktchecker/current-source-linkage-selection/2", "selection_schema")
    # A future per-rule producer needs an explicit reviewed verifier, not a generic
    # "passed" sidecar upgrade or this source snapshot as an execution attestation.
    need(selection.get("selected_current_rule_evidence") == [], "current_rule_evidence_requires_separate_verifier")
    declared, catalog, contexts = load_declared(reader, selection)
    by_rule, by_path = load_mappings(reader, selection, catalog)
    authored, routes, source_count = compiler_linkage(reader, selection, by_rule, by_path)
    snapshot = check_selected_subset(reader, selection, selection_ref)
    rules = []
    for rule_id, entry in sorted(catalog.items()):
        candidates = by_rule[rule_id]
        rule_routes = [route for route in routes if rule_id in route["rule_ids"]]
        gaps = ["fresh_whole_context_per_rule_execution_selection_missing", "independent_qualification_missing"]
        if not candidates:
            gaps.append("no_selected_provider_mapping")
        if any(c["catalog_record_pin_verified"] is None for c in candidates):
            gaps.append("mapping_has_no_per_record_catalog_pin")
        if not any(c["source_implementation_linked"] is True for c in candidates):
            gaps.append("source_implementation_linkage_not_established_in_selected_slice")
        if not rule_routes:
            gaps.append("current_per_rule_fixture_route_not_linked")
        for route in rule_routes:
            if not route["compatible_with_selected_mapping"]:
                gaps.append("ordinary_fixture_route_mapping_contract_mismatch")
            if not route["ordinary_six_kind_source_route"]:
                gaps.append("observation_route_is_not_ordinary_six_kind_coverage")
        kinds = [case["kind"] for case in authored.get(rule_id, {}).get("ordinary_cases", [])]
        rules.append({**entry, "catalog_excluded": entry["default_activation"] == "excluded",
            "mappings": candidates,
            "source_implementation_linked": True if any(c["source_implementation_linked"] is True for c in candidates) else None,
            "route_linked": any(r["compatible_with_selected_mapping"] for r in rule_routes) if rule_routes else None,
            "ordinary_six_kind_route_linked": any(r["compatible_with_selected_mapping"] and r["ordinary_six_kind_source_route"]
                                                    for r in rule_routes) if rule_routes else None,
            "route_ids": [r["route_id"] for r in rule_routes],
            "authored_source_cases": authored.get(rule_id, {}),
            "ordinary_case_kinds_without_selected_source_link": [kind for kind in CASE_KINDS if kind not in kinds],
            "gaps": sorted(set(gaps))})
    cells = [{"rule_id": rule_id, "context_sha256": context_sha,
              "state": "excluded" if catalog[rule_id]["default_activation"] == "excluded" else "current_evidence_unavailable",
              **{stage: None for stage in STAGES}}
             for context_sha in sorted(contexts) for rule_id in sorted(catalog)]
    reader.recheck()
    read_set = [item[0] for _, item in sorted(reader.files.items())]
    return {"schema": "ktchecker/current-source-linkage-report/2", "source_linkage_valid": True,
        "coverage_inventory_valid": False, "release_qualified": False, "activation_changed": False,
        "selection": selection_ref, "source_snapshot": snapshot,
        "scope": {"contract_id": declared["contract_id"], **selection["expected_scope"],
                  "excluded_rules": sum(r["catalog_excluded"] for r in rules),
                  "withdrawn_provider_rule_context_cells_declared": declared["scope"].get("withdrawn_provider_rule_context_cells")},
        "source_summary": {"mapped_rule_ids": sum(bool(r["mappings"]) for r in rules),
            "unmapped_rule_ids": sum(not r["mappings"] for r in rules),
            "provider_rule_rows": sum(len(r["mappings"]) for r in rules),
            "source_implementation_linked_rule_ids": sum(r["source_implementation_linked"] is True for r in rules),
            "selected_compiler_implementation_source_files": source_count,
            "ordinary_authored_pairs": len(authored),
            "ordinary_pairs_blocked_by_mapping_contract": len(authored) if not routes[0]["compatible_with_selected_mapping"] else 0,
            "current_compatible_route_linked_rule_ids": sum(r["route_linked"] is True for r in rules),
            "current_observation_cases_not_six_kind_credit": len(routes[1]["case_ids"])},
        "current_evidence_stage_totals": {stage: None for stage in STAGES},
        "current_evidence_stage_status": "unavailable_not_measured_zero",
        "contexts": [{"context_sha256": key, "context": value} for key, value in sorted(contexts.items())],
        "rules": rules, "routes": routes, "cells": cells,
        "read_set": {"files": len(read_set), "bytes": reader.total, "sha256": sha(canonical(read_set)), "references": read_set},
        "limitations": ["Source linkage only: registry/normalizer references do not establish per-ID diagnostic emission or semantic completeness.",
            "An authored source-case link is not a passed integration case. Unlinked kinds are not proof that no helper/test source exists.",
            "All inherited whole contexts remain unchanged declared obligations, not a complete current release matrix.",
            "The public derived subset verifies exactly 254 selected source files. Its HEAD/dirty identity and original snapshot digest/size/file-count/aggregate are historical owner assertions; original snapshot bytes, the whole tree and loaded code image are not verified here.",
            "Historical checkpoint scopes and stages are not transferred; no legacy artifact target or generic test total is selected.",
            "The compiler-only implementation slice does not activate or qualify bounded semantic library providers, native rules, or other providers.",
            "Future implemented/tested/qualified credit needs a separately reviewed current per-rule producer verifier and exact full-context/source/receipt pins."]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True)
    parser.add_argument("--selection", required=True, help="explicit repository-relative source selection")
    parser.add_argument("--expect-selection-sha256", required=True)
    parser.add_argument("--expect-selection-bytes", required=True, type=int)
    args = parser.parse_args(argv)
    try:
        report = create_report(Reader(args.root), {"path": args.selection,
            "sha256": args.expect_selection_sha256, "bytes": args.expect_selection_bytes})
        encoded = json.dumps(report, sort_keys=True, ensure_ascii=False, allow_nan=False)
        need(len(encoded.encode("utf-8")) <= MAX_OUTPUT, "output_budget_exceeded")
    except (LinkageError, OSError, KeyError, TypeError, ValueError, RecursionError) as error:
        code = str(error) if isinstance(error, LinkageError) else "invalid_or_unreadable_selected_input"
        print("current-source-linkage: " + code, file=sys.stderr)
        return 2
    print(encoded)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

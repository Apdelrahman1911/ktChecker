#!/usr/bin/env python3
"""Select verified authored source plans only. No compiler, provider, process or evidence writer."""
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Callable
import argparse
import hashlib
import json

SELECTOR = "nullability_protocols"
COHORT = "2026-11-04-nullability_protocols"
COMPILER_VERSION = "2.4.21"
FLAGS = ("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4")
KINDS = ("positive", "negative", "boundary", "applicability", "suppression")
MANIFEST_PATH = "cohort-plans/nullability-protocols-v1.json"
SOURCE_EVIDENCE_PATH = "cohort-plans/nullability-protocols-source-evidence.json"
GAP_INVENTORY_PATH = "cohort-plans/nullability-protocols-gap-inventory.json"
SOURCE_EVIDENCE_SHA256 = "2a453246ad9db2c0499c5c2c9cd488261493a786a5185618d91beafeb83ea0b5"
GAP_INVENTORY_SHA256 = "dbdf33758d612786e9422d45551cdb3fda43f3f4a3fad57a7e3fab47f6c03a70"
INPUT_EXECUTION_POLICY = "One unchanged existing authored file per separately authorized sealed job. No concatenation, transforms, batches, retries, replacements, resume or merge; source selection is not execution."
# These literals are source identities, not compiler-emission, ordinal or permit evidence.
REVIEWED_RULES = (
    ('ktc.compiler.common.unsafe_implicit_invoke_call', 'UNSAFE_IMPLICIT_INVOKE_CALL', (
        ('positive', 'cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/positive.kt', '52ce436172dae231416bc02452151f569f8760cb9c0439a1bfac2b1f080ee6ea'),
        ('negative', 'cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/negative.kt', 'afe94061f0e38539f5c6af16eecf10b3e19544725d760ec09c784e310dc3cf9b'),
        ('boundary', 'cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/boundary.kt', '8370b165cbf685e814ae7a02d6ca45d7e5230b349745dde556d95913c2463a51'),
        ('applicability', 'cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/applicability.kt', '1894ba6be39d7b43f2d85970182c4d2d0839c27be4d85e8b10238815ad0cfad6'),
        ('suppression', 'cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/suppression.kt', '8558e3a92bc5f4586a9eae836cf272687617318b483f269fdf3296b1b427ebbc'),
    )),
    ('ktc.compiler.common.unsafe_infix_call', 'UNSAFE_INFIX_CALL', (
        ('positive', 'cohort-plans/nullability-protocols-cases/unsafe_infix_call/positive.kt', '6285cb534f872d68c0461217d1eb9bb080eb7958bb0a3cc1b59b3bfde2e9496e'),
        ('negative', 'cohort-plans/nullability-protocols-cases/unsafe_infix_call/negative.kt', '653b41cebbda3d5eabe8405cb428ee9b328d9b573ebc4d8511297efa805ca562'),
        ('boundary', 'cohort-plans/nullability-protocols-cases/unsafe_infix_call/boundary.kt', '843e37f6f343c5524092c12c4e30d2f29c8209b15fded1463353904c1f1fd2f4'),
        ('applicability', 'cohort-plans/nullability-protocols-cases/unsafe_infix_call/applicability.kt', '7939d9a41264cb8b31219092b0f31ffee70b32f8e7a54dad791c08fbd8f95dd9'),
        ('suppression', 'cohort-plans/nullability-protocols-cases/unsafe_infix_call/suppression.kt', 'e3f44e589b55ed9865957cb93d0aa31e6cb288ef3531ccc3531186816b89be0f'),
    )),
    ('ktc.compiler.common.unsafe_operator_call', 'UNSAFE_OPERATOR_CALL', (
        ('positive', 'cohort-plans/nullability-protocols-cases/unsafe_operator_call/positive.kt', '0b4b77531c01ff2135f6d08e96d315a6e7501642e28999f01bade91a236a63ba'),
        ('negative', 'cohort-plans/nullability-protocols-cases/unsafe_operator_call/negative.kt', '30f620138e400107d6a75fa5a5914c16e6b663f84715de7bbb2a1797d2bb2744'),
        ('boundary', 'cohort-plans/nullability-protocols-cases/unsafe_operator_call/boundary.kt', '204d5ea963c341f84b4d8eed4b153e4996baf09149653292c74301ac896b99ea'),
        ('applicability', 'cohort-plans/nullability-protocols-cases/unsafe_operator_call/applicability.kt', 'c8cd8fe0d4fb73f2c35f613f047bcea6337a750ff06ca5fb0593c021384645bf'),
        ('suppression', 'cohort-plans/nullability-protocols-cases/unsafe_operator_call/suppression.kt', '8982dbd99ed2256c06190a631d319c8322c567cef00da01dda6634f748b60946'),
    )),
    ('ktc.compiler.common.unsafe_callable_reference', 'UNSAFE_CALLABLE_REFERENCE', (
        ('positive', 'cohort-plans/nullability-protocols-cases/unsafe_callable_reference/positive.kt', '5a2f487292a8f3f7eb99450a46701eb7546e74879eaafa4b24c702de3ebcac7c'),
        ('negative', 'cohort-plans/nullability-protocols-cases/unsafe_callable_reference/negative.kt', 'ae6150502d1274c9298f3f8beac7bb2a1d76abd6493f72d2a61023506c654c73'),
        ('boundary', 'cohort-plans/nullability-protocols-cases/unsafe_callable_reference/boundary.kt', '76ac4763a73a7394707edcff444fcf9c6305137b064617f61591135ab526cedc'),
        ('applicability', 'cohort-plans/nullability-protocols-cases/unsafe_callable_reference/applicability.kt', 'e651fdc1199a6dc802b0930ee6ca8e4a76bc7e64aeb84006d9b86059e1629b2c'),
        ('suppression', 'cohort-plans/nullability-protocols-cases/unsafe_callable_reference/suppression.kt', '09af74f7a83980315d267ce8b663be456707b5f342214d9fe8eaa723711acc4c'),
    )),
    ('ktc.compiler.common.iterator_on_nullable', 'ITERATOR_ON_NULLABLE', (
        ('positive', 'cohort-plans/nullability-protocols-cases/iterator_on_nullable/positive.kt', '3e571b48b075a567cde4d06b304159ebce2ee98c5123a33277c630498ef86163'),
        ('negative', 'cohort-plans/nullability-protocols-cases/iterator_on_nullable/negative.kt', '9b4911e6de82cb703c30afc824b75fa66bbd6946504363c232e64107a62fec04'),
        ('boundary', 'cohort-plans/nullability-protocols-cases/iterator_on_nullable/boundary.kt', 'bffaee3477b8cd269a3edc8d83767173f8bae7007579a520f71fa8934e45b3a3'),
        ('applicability', 'cohort-plans/nullability-protocols-cases/iterator_on_nullable/applicability.kt', '7677625ababb895156875d6f172ec555c116515a1f6ad38b90e0bab3d83220f5'),
        ('suppression', 'cohort-plans/nullability-protocols-cases/iterator_on_nullable/suppression.kt', 'e82cc0ca2a434fa8cd893a10b651d02db740c6b160b263af4a9d5b26e3cd5079'),
    )),
    ('ktc.compiler.common.unexpected_safe_call', 'UNEXPECTED_SAFE_CALL', (
        ('positive', 'cohort-plans/nullability-protocols-cases/unexpected_safe_call/positive.kt', 'abcfbe94c77b33690b2ced052fff55c0c6a14db41b248ca241f81f09c271a3c4'),
        ('negative', 'cohort-plans/nullability-protocols-cases/unexpected_safe_call/negative.kt', 'caac88241f68eb0ab07f681408f50155fea8bb8d8bbc282b54da9b8303a7797c'),
        ('boundary', 'cohort-plans/nullability-protocols-cases/unexpected_safe_call/boundary.kt', 'eb6a0212a10caf44fdd1447f19a4f32e29134f4fdf3aae9a01f99fad7f83eadf'),
        ('applicability', 'cohort-plans/nullability-protocols-cases/unexpected_safe_call/applicability.kt', '5454aca7e046292a36febc10710550a262dfe661f2372ab04e164c568ecd2f7b'),
        ('suppression', 'cohort-plans/nullability-protocols-cases/unexpected_safe_call/suppression.kt', '33c9235a65140044479c14f55f8dc04429e0942de4af692cd441a8280ec280d2'),
    )),
 )
RULE_IDS = tuple(rule_id for rule_id, _, _ in REVIEWED_RULES)
SOURCES = {(rule_id, kind): (path, digest)
           for rule_id, _, variants in REVIEWED_RULES for kind, path, digest in variants}
REVIEWED_SOURCE_ANCHORS = {
    ('ktc.compiler.common.unsafe_implicit_invoke_call', 'positive'): (5, 43, 'action'),
    ('ktc.compiler.common.unsafe_implicit_invoke_call', 'suppression'): (6, 43, 'action'),
    ('ktc.compiler.common.unsafe_infix_call', 'positive'): (7, 53, 'add'),
    ('ktc.compiler.common.unsafe_infix_call', 'suppression'): (8, 53, 'add'),
    ('ktc.compiler.common.unsafe_operator_call', 'positive'): (7, 56, '+'),
    ('ktc.compiler.common.unsafe_operator_call', 'suppression'): (8, 56, '+'),
    ('ktc.compiler.common.unsafe_callable_reference', 'positive'): (7, 67, 'score'),
    ('ktc.compiler.common.unsafe_callable_reference', 'suppression'): (8, 67, 'score'),
    ('ktc.compiler.common.iterator_on_nullable', 'positive'): (14, 19, 'range'),
    ('ktc.compiler.common.iterator_on_nullable', 'suppression'): (15, 19, 'range'),
    ('ktc.compiler.common.unexpected_safe_call', 'positive'): (8, 33, '?.'),
    ('ktc.compiler.common.unexpected_safe_call', 'suppression'): (9, 33, '?.'),
}
FACTORY_LINES = (919, 920, 921, 922, 923, 925)
BASELINE_SOURCE_PINS = (
    ('providers/compiler-fixtures/manifest.json', '4854aa356caba6e90b48e10e333bf32828460dc64b04dd0d6b86db3653d49774'),
    ('providers/compiler-fixtures/planned-kmp-manifest.json', '804c242c62707457ff143d16fb03bcd1bddd2ca84b3f9aef2085dc5af800b128'),
    ('providers/compiler-fixtures/authored-input-inventory.json', 'b8c6af0b0f1d68578ad9a209e900c83668fc25f67bfd7fbf272fc28720511bad'),
    ('providers/compiler-fixtures/generate_fixtures.py', '73cfab45c982081da4088daa6fc2b266f16d24c828010772966ea201d1372b69'),
    ('providers/compiler-fixtures/cohort_expansion.py', '2dd0d0323b507da9f4ff3e758ab0951f53fcdf196a46006331e5e33681f33edb'),
 )
PREEXISTING_FIXTURE_INVENTORY_SHA256 = "378127f94364be6ac94bf84d56769ea25126f28840686f03fa5adafc55c7bc00"
SOURCE_ONLY_COUNTS = {
    "existing_main_manifest_ordinary_pairs": 98,
    "existing_planned_unsupported_graphs": 16,
    "existing_exact_mappings": 443,
    "new_additive_source_rule_plans": 6,
    "new_authored_files": 30,
    "prospective_ordinary_pairs_only_if_lead_approves_append": 104,
    "current_remaining_mapped_gaps": 329,
    "prospective_remaining_mapped_gaps_if_source_plans_append": 323,
    "current_remaining_mapped_by_backend": {"common": 202, "ir": 8, "jvm": 76, "native": 43},
    "prospective_remaining_mapped_by_backend": {"common": 196, "ir": 8, "jvm": 76, "native": 43},
    "preexisting_kotlin_fixture_sources": 278,
    "combined_authored_kotlin_sources_in_this_sparse_copy": 308,
    "existing_type_boundary_authored_supplemental_files": 18,
    "new_source_selected_compiler_calls": 0,
    "new_source_selector_provider_reservations": 0,
    "new_six_kind_completion": False,
    "new_qualification_count": 0,
}


def require(condition: bool, message: str) -> None:
    # Do not use assert: fail-closed source validation must also work under python -O.
    if not condition:
        raise ValueError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def logical_path(relative: str) -> PurePosixPath:
    require(isinstance(relative, str) and relative != "" and "\\" not in relative and ":" not in relative and "\x00" not in relative,
            "Invalid logical source path")
    path = PurePosixPath(relative)
    require(not path.is_absolute() and ".." not in path.parts and path.as_posix() == relative,
            "Source path must be exact relative POSIX spelling")
    return path


def read_existing_source(fixtures: Path, relative: str) -> bytes:
    path = logical_path(relative)
    base = fixtures.resolve()
    target = base.joinpath(*path.parts)
    require(target.is_file() and not target.is_symlink() and target.resolve() == target,
            "Source must be an exact existing regular no-symlink file")
    return target.read_bytes()


@dataclass(frozen=True)
class SelectedSource:
    source_position: int
    rule_id: str
    upstream_id: str
    kind: str
    path: str
    source_sha256: str
    source_bytes: bytes

    def description(self) -> dict:
        return {"source_selection_position": self.source_position,
                "position_is_not_an_attempt_number": True,
                "rule_id": self.rule_id, "upstream_id": self.upstream_id, "kind": self.kind,
                "authored_path": self.path, "source_sha256": self.source_sha256,
                "compiler_flags": list(FLAGS), "source_presence_and_hash_checked": True,
                "initial_authoring_execution_state": "not_run",
                "compiler_execution_witness": False, "ordinary_six_kind_assessment": False,
                "suppression_expectation_validation": "unknown_unvalidated" if self.kind == "suppression" else "proposed_not_observed",
                "qualified": False}


def _compilation_expectation(kind: str) -> str:
    return ("unknown_preserve_actual_failure_or_compiler_accepted_suppression" if kind == "suppression" else
            "proposed_nonzero_not_observed" if kind == "positive" else "proposed_exit0_not_observed")


def _diagnostic_expectation(kind: str) -> str:
    return ("unknown_preserve_actual_emission_or_suppression" if kind == "suppression" else
            "proposed_selected_id_present_not_observed" if kind == "positive" else
            "proposed_selected_id_absent_not_observed")


def select_sources(selector: str, manifest: dict, read_source: Callable[[str], bytes]) -> tuple[SelectedSource, ...]:
    """Explicit source selection only. All 30 bytes are read independently; no job is scheduled."""
    require(selector == SELECTOR, "Only the explicit reviewed nullability_protocols source selector is accepted; no default/all")
    require(manifest.get("schema_version") == 1 and manifest.get("kotlin_version") == COMPILER_VERSION and
            manifest.get("selector") == SELECTOR and manifest.get("authored_cohort") == COHORT and
            manifest.get("status") == "authored_unscheduled_source_only_additive_plan", "Unreviewed cohort manifest")
    require(manifest.get("ordered_kinds") == list(KINDS) and manifest.get("flags") == list(FLAGS),
            "Reviewed five source kinds or explicit JVM17/language/API2.4 flags changed")
    require(manifest.get("ordinary_main_manifest_mutation") is False and
            manifest.get("map_catalog_or_runner_mutation") is False and
            manifest.get("new_kind_inventory_is_not_ordinary_six_kind_completion") is True and
            manifest.get("run_complete") is False and manifest.get("qualified") is False,
            "Source plans cannot become ordinary completion or qualification")
    profile = manifest.get("target_profile", {})
    require(profile.get("compiler_version") == COMPILER_VERSION and profile.get("language_version") == "2.4" and
            profile.get("api_version") == "2.4" and profile.get("backend") == "jvm" and profile.get("jvm_target") == "17" and
            profile.get("explicit_compiler_flags") == list(FLAGS) and profile.get("additional_checker_activation_flags") == [] and
            profile.get("plugins") == [] and profile.get("java_sources") == [] and profile.get("user_or_test_libraries") == [] and
            profile.get("project_settings_authority") is False,
            "Unknown module/platform/plugin/library/checker profile is not this authored source plan")
    require(manifest.get("execution_schedule") == "none; a future exact bounded schedule requires separate lead authorization and source review",
            "Source selection is not an execution schedule")
    book = manifest.get("bookkeeping", {})
    require(all(book.get(key) == value and type(book.get(key)) is type(value) for key, value in SOURCE_ONLY_COUNTS.items()),
            "Explicit source-only all-count bookkeeping changed")
    require(book.get("scope") == "source_presence_and_manifest_arithmetic_only_not_execution_or_coverage" and
            book.get("existing_supplemental_execution_results_must_remain_in_their_own_tuple") is True,
            "Historical tuples must not be merged into this source plan")
    rows = manifest.get("fixtures", [])
    require(len(rows) == 6 and {row.get("rule_id") for row in rows} == set(RULE_IDS), "Exact six reviewed source-plan IDs required")
    by_id = {row["rule_id"]: row for row in rows}
    records = {}
    for index, (rule_id, upstream_id, variants) in enumerate(REVIEWED_RULES):
        row = by_id[rule_id]
        expected_factory = ("https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/"
                            "compiler/fir/checkers/gen/org/jetbrains/kotlin/fir/analysis/diagnostics/FirErrors.kt#L" + str(FACTORY_LINES[index]))
        require(row.get("upstream_id") == upstream_id and row.get("backend") == "common" and row.get("declared_severity") == "error" and
                row.get("authored_cohort") == COHORT and row.get("flags") == list(FLAGS) and row.get("source") == expected_factory and
                row.get("status") == "unrun_additive_cohort_source_plan" and row.get("ordinary_six_kind_assessment") is False,
                "Unreviewed diagnostic/severity/authoring profile: " + rule_id)
        require(row.get("positive") == SOURCES[(rule_id, "positive")][0] and row.get("negative") == SOURCES[(rule_id, "negative")][0],
                "Ordinary pair proposal paths changed")
        inputs = row.get("inputs", [])
        require(len(inputs) == 5 and {entry.get("kind") for entry in inputs} == set(KINDS), "Exactly five unique authored variants required")
        for entry in inputs:
            kind = entry["kind"]
            expected_path, expected_digest = SOURCES[(rule_id, kind)]
            require(entry.get("path") == expected_path and entry.get("source_sha256") == expected_digest and
                    entry.get("compiler_flags") == list(FLAGS), "Literal authored path/digest/flags changed")
            logical_path(expected_path)
            require(entry.get("status") == "unrun_independent_next_cohort_source_plan" and entry.get("execution_state") == "not_run" and
                    entry.get("executed_by_current_pair_harness") is False and entry.get("expected_outcome_matched") is None and
                    entry.get("qualification") == "none", "Initial authoring status is not a mutable execution receipt")
            require(entry.get("compilation_expectation") == _compilation_expectation(kind) and
                    entry.get("diagnostic_expectation") == _diagnostic_expectation(kind) and
                    entry.get("proposed_selected_id") == (upstream_id if kind in ("positive", "suppression") else None) and
                    entry.get("proposed_expectation", "").startswith("Proposed: ") and
                    entry.get("execution_policy") == INPUT_EXECUTION_POLICY,
                    "Keep hard-error suppression unknown and all other expectations explicitly proposed")
            records[(rule_id, kind)] = entry
    selected = []
    for kind in KINDS:
        for rule_id, upstream_id, _ in REVIEWED_RULES:
            entry = records[(rule_id, kind)]
            path, expected = SOURCES[(rule_id, kind)]
            data = read_source(path)
            require(isinstance(data, bytes) and sha256(data) == expected and b"\r" not in data and data.endswith(b"\n"),
                    "Existing authored UTF-8/LF source bytes differ from the reviewed digest: " + path)
            text_lines = data.decode("utf-8").splitlines()
            anchor = entry.get("proposed_source_anchor")
            if kind in ("positive", "suppression"):
                require(isinstance(anchor, dict) and type(anchor.get("line")) is int and type(anchor.get("column")) is int and
                        (anchor.get("line"), anchor.get("column"), anchor.get("token")) == REVIEWED_SOURCE_ANCHORS[(rule_id, kind)] and
                        1 <= anchor["line"] <= len(text_lines) and anchor["column"] >= 1 and
                        text_lines[anchor["line"] - 1][anchor["column"] - 1:].startswith(anchor.get("token", "")) and
                        anchor.get("purpose") == "proposed_source_anchor_only_not_an_observed_diagnostic_location",
                        "Proposed source anchor changed; it is not an actual diagnostic point")
            else:
                require(anchor is None, "Selected-ID absence plans have no invented observed diagnostic point")
            selected.append(SelectedSource(len(selected) + 1, rule_id, upstream_id, kind, path, expected, data))
    return tuple(selected)


def validate_bookkeeping(repository: Path, manifest: dict) -> dict:
    """Source inventories only; mapping/fixture availability cannot promote tested/qualified state."""
    repository = repository.resolve()
    fixtures = repository / "providers/compiler-fixtures"
    for relative, expected in BASELINE_SOURCE_PINS:
        require(sha256(read_existing_source(repository, relative)) == expected, "Preexisting source authoring bytes changed: " + relative)
    ordinary = json.loads(read_existing_source(fixtures, "manifest.json"))
    planned = json.loads(read_existing_source(fixtures, "planned-kmp-manifest.json"))
    mappings = json.loads(read_existing_source(repository, "modules/provider-compiler/src/main/resources/compiler-rule-map.json"))
    ordinary_ids = {row["rule_id"] for row in ordinary["fixtures"]}
    planned_ids = {row["rule_id"] for row in planned["fixtures"]}
    mapped = {row["rule_id"]: row for row in mappings["mappings"]}
    require(len(ordinary["fixtures"]) == len(ordinary_ids) == 98 and len(planned["fixtures"]) == len(planned_ids) == 16 and
            len(mappings["mappings"]) == len(mapped) == 443 and mappings.get("provider_version") == COMPILER_VERSION,
            "Do not overwrite or pad the existing98, graph16 or exact443 map")
    require(not ordinary_ids & planned_ids and not set(RULE_IDS) & (ordinary_ids | planned_ids) and set(RULE_IDS) <= mapped.keys(),
            "Additive source plan must not replace an existing ordinary/planned mapping")
    require(all(mapped[rule_id]["upstream_id"] == upstream_id and mapped[rule_id]["backend"] == "common" and
                mapped[rule_id]["declared_severity"] == "ERROR" and mapped[rule_id]["known_checker_activation_flags"] == []
                for rule_id, upstream_id, _ in REVIEWED_RULES), "Selected exact map contracts changed")
    # The new standalone source directory is deliberately outside these historical roots.
    preexisting = sorted(list((fixtures / "cases").rglob("*.kt")) +
                         list((fixtures / "planned-kmp-cases").rglob("*.kt")), key=lambda p: p.relative_to(fixtures).as_posix())
    require(len(preexisting) == 278 and all(p.is_file() and not p.is_symlink() and p.resolve() == p for p in preexisting),
            "Preexisting 278 authored Kotlin source inventory changed")
    inventory = "".join(sha256(p.read_bytes()) + "  " + p.relative_to(fixtures).as_posix() + "\n" for p in preexisting).encode("utf-8")
    require(sha256(inventory) == PREEXISTING_FIXTURE_INVENTORY_SHA256, "Preserve all existing fixture bytes including the supplemental18")
    variants = [entry for row in ordinary["fixtures"] for entry in row.get("supplemental_inputs", [])]
    require(len(variants) == 18 and all(entry["status"] == "unrun_supplemental_source_plan" and
            entry["execution_state"] == "not_run" and entry["executed_by_current_pair_harness"] is False for entry in variants),
            "Never retrospectively relabel the initial type-boundary supplemental records")
    gaps = json.loads(read_existing_source(fixtures, GAP_INVENTORY_PATH))
    evidence = json.loads(read_existing_source(fixtures, SOURCE_EVIDENCE_PATH))
    require(sha256(read_existing_source(fixtures, GAP_INVENTORY_PATH)) == GAP_INVENTORY_SHA256 and
            sha256(read_existing_source(fixtures, SOURCE_EVIDENCE_PATH)) == SOURCE_EVIDENCE_SHA256,
            "Frozen source gap/evidence metadata changed")
    require(evidence.get("kind") == "pinned_public_source_docs_API_and_tagged_marker_evidence_not_execution_receipts" and
            evidence.get("source_tag") == "v2.4.21" and evidence.get("no_gradle_jvm_compiler_native_provider_execution") is True,
            "Tagged docs/API evidence is not compiler execution evidence")
    actual_gaps = {rule_id: row for rule_id, row in mapped.items() if rule_id not in ordinary_ids | planned_ids}
    gap_rows = {row["rule_id"]: row for row in gaps["remaining_mapped_gaps"]}
    require(len(actual_gaps) == len(gaps["remaining_mapped_gaps"]) == len(gap_rows) == 329 and actual_gaps.keys() == gap_rows.keys(),
            "Keep every remaining mapped ID visible")
    fields = ("rule_id", "upstream_id", "backend", "declared_severity", "source_section", "source", "known_checker_activation_flags")
    require(all(all(gap_rows[rule_id][field] == row[field] for field in fields) and
                gap_rows[rule_id]["selected_for_this_additive_source_plan"] is (rule_id in RULE_IDS) and
                gap_rows[rule_id]["actual_execution_or_qualification_claim"] is False
                for rule_id, row in actual_gaps.items()), "Gap classification cannot become execution/qualification")
    require(manifest["bookkeeping"] == gaps["bookkeeping"] and
            all(manifest["bookkeeping"].get(key) == value for key, value in SOURCE_ONLY_COUNTS.items()),
            "Standalone inventories disagree on explicit all-count arithmetic")
    return manifest["bookkeeping"].copy()


def describe_source_selection(selected: tuple[SelectedSource, ...], bookkeeping: dict) -> dict:
    expected = [(rule_id, upstream_id, kind, path, digest)
                for kind in KINDS for rule_id, upstream_id, variants in REVIEWED_RULES
                for source_kind, path, digest in variants if source_kind == kind]
    require(len(selected) == 30 and all(
            (entry.rule_id, entry.upstream_id, entry.kind, entry.path, entry.source_sha256) == expected[index] and
            entry.source_position == index + 1 and sha256(entry.source_bytes) == entry.source_sha256
            for index, entry in enumerate(selected)), "Exact ordered authored source identities/bytes required; a count is not source evidence")
    require(all(bookkeeping.get(key) == value and type(bookkeeping.get(key)) is type(value)
                for key, value in SOURCE_ONLY_COUNTS.items()), "Source descriptions cannot promote bookkeeping")
    return {"schema_version": 1, "selector": SELECTOR, "source_only": True,
            "all30_authored_sources_present_and_hash_checked": True,
            "source_position_is_not_provider_reservation_or_execution": True,
            "compiler_calls_launched_by_this_helper": 0, "provider_reservations_made": 0,
            "no_compiler_execution_authority": True, "executable_schedule": None,
            "ordinary_six_kind_assessments_complete": False, "run_complete": False,
            "suppression_expectations": "unknown_unvalidated", "qualified": False,
            "ordinary_external_latest_sidecar_writes": False,
            "bookkeeping": bookkeeping, "sources": [entry.description() for entry in selected]}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selector", required=True, choices=(SELECTOR,), help="Required reviewed SOURCE selector; never schedules jobs")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2], help="Read-only repository/source-review root")
    args = parser.parse_args(argv)
    fixtures = args.root.resolve() / "providers/compiler-fixtures"
    manifest = json.loads(read_existing_source(fixtures, MANIFEST_PATH))
    counts = validate_bookkeeping(args.root, manifest)
    selected = select_sources(args.selector, manifest, lambda relative: read_existing_source(fixtures, relative))
    print(json.dumps(describe_source_selection(selected, counts), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

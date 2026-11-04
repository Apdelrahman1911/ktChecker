#!/usr/bin/env python3
"""Retain verified actual XML-control receipts; never manufacture missing case kinds."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RULES = {
    "ktc.android.lint.ContentDescription": "ContentDescription",
    "ktc.android.lint.HardcodedText": "HardcodedText",
}
IMPLEMENTATION = "modules/provider-lint/src/main/kotlin/dev/ktchecker/providers/lint/AndroidLintProvider.kt"


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def valid_digest(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", value) is not None


def reference(path: Path) -> dict:
    resolved = path.resolve(strict=True)
    return {"path": resolved.relative_to(ROOT).as_posix(), "sha256": digest(resolved)}


def receipt(path: Path, case: str) -> dict:
    value = json.loads(path.read_text())
    assert value["schemaVersion"] == 1 and value["state"] == "passed" and value["case"] == case
    assert value["target"] == "android" and valid_digest(value["snapshotId"])
    provider = value["providerResult"]
    assert provider["providerId"] == "android-lint" and provider["providerVersion"] == "32.3.1"
    assert provider["status"] == "incomplete" and provider["rawDiagnostics"] == []
    rows = provider["coverage"]
    assert {row["ruleId"] for row in rows} == set(RULES)
    assert all(row["state"] == "incomplete" and row["provenance"] == "executed"
               and valid_digest(row["certificateId"]) and row["tested"] is False
               and row["qualified"] is False for row in rows)
    findings = provider["findings"]
    assert {finding["upstreamId"] for finding in findings} == (set(RULES.values()) if case == "positive" else set())
    source = ROOT / "providers/platform-fixtures/android" / case / "res/layout/example.xml"
    for finding in findings:
        assert RULES[finding["ruleId"]] == finding["upstreamId"] and finding["context"]["target"] == "android"
        assert finding["location"]["path"] == "res/layout/example.xml"
        assert finding["location"]["fileDigest"] == "sha256:" + digest(source)
        assert type(finding["location"]["line"]) is int and finding["location"]["line"] > 1
    artifacts = value["artifactIdentities"]
    assert artifacts and all(valid_digest(artifact["digest"]) and
                             digest(Path(artifact["path"])) == artifact["digest"].removeprefix("sha256:")
                             for artifact in artifacts)
    # Receipt artifacts were actually selected and checked by the driver. Host paths,
    # commands/environment, messages and excerpts are deliberately not copied.
    safe_provider = {name: provider[name] for name in ("providerId", "providerVersion", "status", "coverage", "reasons", "metrics")}
    safe_provider["findings"] = [
        {name: finding[name] for name in ("ruleId", "providerId", "upstreamId", "severity", "context", "fingerprint")}
        | {"location": {name: finding["location"][name] for name in
                         ("path", "fileDigest", "line", "column", "endLine", "endColumn")}}
        for finding in findings
    ]
    safe_provider["rawDiagnostics"] = []
    fixture_refs = [reference(file) for file in sorted(source.parents[2].rglob("*")) if file.is_file()]
    return {"schemaVersion": 1, "state": "passed", "case": case, "target": "android",
            "snapshotId": value["snapshotId"], "providerResult": safe_provider,
            "artifactIdentities": [{"coordinate": artifact["coordinate"], "digest": artifact["digest"],
                                    "fileName": Path(artifact["path"]).name} for artifact in artifacts],
            "original_private_envelope_sha256": digest(path), "fixture_source_references": fixture_refs,
            "assertions": value["assertions"], "limitations": value["limitations"] + [
                "Post-run retention verifies existing receipt/artifact/source identities, not live-current capture or source-to-bytecode qualification.",
                "Host paths, commands/environment, source messages, excerpts and raw terminal output omitted.",
            ]}


def write(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for case in ("positive", "negative", "suppression"):
        parser.add_argument("--" + case, required=True, type=Path)
    parser.add_argument("--junit", required=True, type=Path)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    assert re.fullmatch(r"[a-zA-Z0-9_.-]+", args.run_id)
    suite = ET.parse(args.junit).getroot()
    assert suite.attrib["name"] == "dev.ktchecker.providers.lint.RealLintFixtureTest"
    assert suite.attrib["tests"] == "2" and all(suite.attrib[key] == "0" for key in ("failures", "errors", "skipped"))
    assert any("actual lint32_3_1" in case.attrib["name"] for case in suite.findall("testcase"))
    # Validate every receipt before creating any evidence files.
    receipts = {case: receipt(getattr(args, case), case) for case in ("positive", "negative", "suppression")}
    run = ROOT / "providers/platform-fixtures/runs" / args.run_id
    run.mkdir(parents=True, exist_ok=False)
    for case, value in receipts.items():
        write(run / (case + ".json"), value)
    write(run / "run.json", {
        "schema_version": 1, "evidence_kind": "actual_isolated_lint_xml_controls", "provider_version": "32.3.1",
        "retained_at": datetime.now(timezone.utc).isoformat(), "test_suite_started_at": suite.attrib["timestamp"],
        "junit_sha256": digest(args.junit), "tests": 2, "failures": 0, "errors": 0, "skipped": 0,
        "test_names": [case.attrib["name"] for case in suite.findall("testcase")],
        "qualifies_rules": False, "qualifies_release": False,
        "source_provenance": "Post-run retention of existing receipt/fixture identities; no source-to-executed-bytecode qualification proof.",
        "current_implementation_references": [reference(ROOT / IMPLEMENTATION)],
    })
    sidecar = {
        "schema_version": 1, "provider_id": "android-lint", "provider_version": "32.3.1/32.5.0",
        "context": {"id": "isolated-lint32.3.1-xml-main", "target": "android", "source_set": "main",
                    "module": ":fixture", "compilation": "xml", "kotlin_version": "not_used_xml_only",
                    "jdk_version": "unknown", "library_versions": {"android-lint": "32.3.1", "lint-client": "9.3.1"},
                    "run": run.relative_to(ROOT).as_posix(),
                    "fixture_scope": "Two XML detector controls only; no Android Gradle or Kotlin/Native execution.",
                    "evidence_state": "verified_xml_controls_unqualified"},
        "rules": [{"rule_id": rule_id, "implemented": True, "tested": False, "qualified": False,
                   "provider_status": "incomplete",
                   "cases": [{"kind": case, "passed": True, "evidence": [reference(run / (case + ".json"))]}
                             for case in ("positive", "negative", "suppression")],
                   "implementation_evidence": [reference(ROOT / IMPLEMENTATION)], "qualification_evidence": [],
                   "gaps": ["Only positive, legal negative, and provider-suppression XML controls executed; configuration/version/regression detector controls missing.",
                            "Actual Lint32.3.1/client9.3.1 only: no Lint32.5.0 detector execution or qualification.",
                            "Standalone JVM version was not independently probed; no complete project/toolchain qualification tuple.",
                            "Synthetic 609-ID transport tests are not detector cases, Native execution, precision/corpus review, or release coverage."]}
                  for rule_id in RULES],
    }
    evidence = ROOT / "providers/platform-fixtures/evidence"
    evidence.mkdir(exist_ok=True)
    write(evidence / (args.run_id + ".json"), sidecar)
    print(f"Retained actual three XML cases for {len(RULES)} IDs; tested=false, qualified=false.")


if __name__ == "__main__":
    main()

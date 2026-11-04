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
XML_SCOPE = {"target": "android", "sourceSet": "main", "module": ":fixture", "compilation": "xml"}
PENDING = "per_id_precision_and_corpus_qualification_pending"
TEST_NAMES = (
    "actual lint32_3_1 diagnoses positive and accepts legal and provider suppression controls()",
    "prerequisite failures produce structured unavailable output not an absent report()",
)


def require(condition: bool, message: str) -> None:
    # Evidence validation must not disappear under python -O.
    if not condition:
        raise ValueError(message)


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
    require(value["schemaVersion"] == 1 and value["state"] == "passed" and value["case"] == case, "Not a passed selected fixture receipt")
    require(value["target"] == "android" and valid_digest(value["snapshotId"]), "Missing Android snapshot identity")
    provider = value["providerResult"]
    require(provider["providerId"] == "android-lint" and provider["providerVersion"] == "32.3.1", "Wrong provider/version")
    require(provider["status"] == "incomplete" and provider["rawDiagnostics"] == [], "Provider receipt is not an unqualified successful control")
    require(provider["reasons"] == [PENDING], "Unexpected provider gaps or private text")
    rows = provider["coverage"]
    require(len(rows) == len(RULES) and {row["ruleId"] for row in rows} == set(RULES), "Exact two-ID coverage is required")
    require(all(row["state"] == "incomplete" and row["provenance"] == "executed" and row["context"] == XML_SCOPE
                and row["reason"] == PENDING and valid_digest(row["certificateId"]) and row["implemented"] is True
                and row["required"] is True and row["depth"] == "syntax" and row["tested"] is False
                and row["qualified"] is False for row in rows), "Coverage cannot claim qualification or another context")
    findings = provider["findings"]
    require(len(findings) == (len(RULES) if case == "positive" else 0) and
            {finding["upstreamId"] for finding in findings} == (set(RULES.values()) if case == "positive" else set()), "Wrong detector control outcome")
    source = ROOT / "providers/platform-fixtures/android" / case / "res/layout/example.xml"
    for finding in findings:
        require(RULES[finding["ruleId"]] == finding["upstreamId"] and finding["context"] == XML_SCOPE, "Finding identity/context mismatch")
        require(finding["providerId"] == "android-lint" and finding["severity"] == "warning" and valid_digest(finding["fingerprint"]), "Unexpected XML finding identity")
        require(finding["location"]["path"] == "res/layout/example.xml", "Wrong fixture source")
        require(finding["location"]["fileDigest"] == "sha256:" + digest(source), "Source digest mismatch")
        location = finding["location"]
        require(all(type(location[field]) is int for field in ("line", "column", "endLine", "endColumn")), "Source range must contain integers, not arbitrary text or booleans")
        line, column, end_line, end_column = (location[field] for field in ("line", "column", "endLine", "endColumn"))
        source_lines = source.read_bytes().decode("utf-8").split("\n")
        require(1 < line <= end_line <= len(source_lines) and column >= 0 and end_column >= 0 and
                (end_line != line or end_column >= column), "Invalid or unordered actual source range")
        # Match the Kotlin source resolver's UTF-16 columns without copying source text.
        width = lambda text: len(text.removesuffix("\r").encode("utf-16-le")) // 2
        require(column <= width(source_lines[line - 1]) and end_column <= width(source_lines[end_line - 1]), "Source range exceeds captured source")
    artifacts = value["artifactIdentities"]
    require(bool(artifacts) and all(valid_digest(artifact["digest"]) and
                                  digest(Path(artifact["path"])) == artifact["digest"].removeprefix("sha256:")
                                  for artifact in artifacts), "Selected artifact bytes mismatch")
    require(all(artifact["coordinate"] in {"com.android.tools.lint:lint-checks:32.3.1", "fixture-lint-executable"}
                or artifact["coordinate"] == "isolated-lint-runtime:" + Path(artifact["path"]).name
                for artifact in artifacts), "Unexpected non-XML artifact identity")
    # Receipt artifacts were actually selected and checked by the driver. Host paths,
    # commands/environment, messages and excerpts are deliberately not copied.
    safe_provider = {name: provider[name] for name in ("providerId", "providerVersion", "status")}
    safe_provider["coverage"] = [{name: row[name] for name in ("ruleId", "context", "state", "reason", "required", "depth",
                                                            "provenance", "certificateId", "implemented", "tested", "qualified")}
                                 for row in rows]
    safe_provider["reasons"] = [PENDING]
    metrics = {name: provider["metrics"][name] for name in ("elapsedMillis", "cpuMillis", "peakRssBytes", "outputBytes")}
    require(all(value is None or type(value) is int and value >= 0 for value in metrics.values()), "Invalid numeric execution metrics")
    safe_provider["metrics"] = metrics
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
            "assertions": ["Validated the expected two-ID Lint32.3.1/client9.3.1 XML receipt for this control.",
                           "Private envelopes and passing JUnit are manually associated post-run, not directly run-ID bound."],
            "limitations": [
                "Only explicit XML controls; no general Android project import, Native execution, or release qualification.",
                "Same-user permissions and trusted process groups are cooperative isolation, not a security sandbox.",
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
    require(re.fullmatch(r"[a-zA-Z0-9_.-]+", args.run_id) is not None, "Unsafe retention run ID")
    suite = ET.parse(args.junit).getroot()
    require(suite.attrib["name"] == "dev.ktchecker.providers.lint.RealLintFixtureTest", "Wrong real control JUnit suite")
    require(suite.attrib["tests"] == "2" and all(suite.attrib[key] == "0" for key in ("failures", "errors", "skipped")), "Actual control suite did not pass")
    cases = suite.findall("testcase")
    require(sorted(case.attrib["name"] for case in cases) == sorted(TEST_NAMES), "Exact maintained XML control test names are required")
    require(all(not any(case.find(outcome) is not None for outcome in ("failure", "error", "skipped")) for case in cases), "Actual XML control testcase did not pass")
    timestamp = suite.attrib["timestamp"]
    require(re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})?", timestamp) is not None,
            "Invalid JUnit timestamp")
    datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    # Validate every receipt before creating any evidence files.
    receipts = {case: receipt(getattr(args, case), case) for case in ("positive", "negative", "suppression")}
    source_bytes = (ROOT / IMPLEMENTATION).read_bytes()
    run = ROOT / "providers/platform-fixtures/runs" / args.run_id
    run.mkdir(parents=True, exist_ok=False)
    archive = run / "post-run-source/AndroidLintProvider.kt"
    archive.parent.mkdir()
    archive.write_bytes(source_bytes)
    implementation_reference = reference(archive)
    for case, value in receipts.items():
        write(run / (case + ".json"), value)
    write(run / "run.json", {
        "schema_version": 1, "evidence_kind": "actual_isolated_lint_xml_controls", "provider_version": "32.3.1",
        "retained_at": datetime.now(timezone.utc).isoformat(), "test_suite_started_at": timestamp,
        "junit_sha256": digest(args.junit), "tests": 2, "failures": 0, "errors": 0, "skipped": 0,
        "test_names": list(TEST_NAMES),
        "qualifies_rules": False, "qualifies_release": False,
        "source_provenance": "Exact post-run source archive is not startup source provenance or source-to-executed-bytecode qualification.",
        "receipt_association": "Post-run manually associated private envelopes and passing JUnit; no direct run-ID/raw-report/command attribution proof.",
        "post_run_source_archive_references": [implementation_reference],
    })
    sidecar = {
        "schema_version": 1, "provider_id": "android-lint", "provider_version": "32.3.1/32.5.0",
        "context": {"id": "isolated-lint32.3.1-xml-main", "target": "android", "source_set": "main",
                    "module": ":fixture", "compilation": "xml", "kotlin_version": "not_used_xml_only",
                    "jdk_version": "unknown", "library_versions": {"android-lint": "32.3.1", "lint-client": "9.3.1"},
                    "run": run.relative_to(ROOT).as_posix(),
                    "fixture_scope": "Two XML detector controls only; no Android Gradle or Kotlin/Native execution.",
                    "evidence_state": "verified_xml_controls_unqualified_post_run_manual_association"},
        "rules": [{"rule_id": rule_id, "implemented": True, "tested": False, "qualified": False,
                   "provider_status": "incomplete",
                   "cases": [{"kind": case, "passed": True, "evidence": [reference(run / (case + ".json"))]}
                             for case in ("positive", "negative", "suppression")],
                   "implementation_evidence": [implementation_reference], "qualification_evidence": [],
                   "gaps": ["Only positive, legal negative, and provider-suppression XML controls executed; configuration/version/regression detector controls missing.",
                            "Actual Lint32.3.1/client9.3.1 only: no Lint32.5.0 detector execution or qualification.",
                            "Standalone JVM version was not independently probed; no complete project/toolchain qualification tuple.",
                            "Synthetic 609-ID transport tests are not detector cases, Native execution, precision/corpus review, or release coverage.",
                            "Exact source bytes archived post-run; manually associated JUnit/private receipts have no direct run-ID or source-to-bytecode attribution proof."]}
                  for rule_id in RULES],
    }
    evidence = ROOT / "providers/platform-fixtures/evidence"
    evidence.mkdir(exist_ok=True)
    write(evidence / (args.run_id + ".json"), sidecar)
    print(f"Retained three manually associated XML-control receipts for {len(RULES)} IDs; tested=false, qualified=false.")


if __name__ == "__main__":
    main()

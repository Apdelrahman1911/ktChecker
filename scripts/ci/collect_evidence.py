#!/usr/bin/env python3
"""Collect actual reports/artifact hashes; missing evidence is never a green check."""

import argparse
import glob
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import xml.etree.ElementTree as ET

if __package__:
    from .ci_common import REPO_ROOT, canonical_digest, digest_bytes, digest_file, source_identity, utc_now, write_json
else:
    from ci_common import REPO_ROOT, canonical_digest, digest_bytes, digest_file, source_identity, utc_now, write_json


def label(value: str | None) -> str:
    """Only bounded report identifiers, not captured source, messages or stdout."""
    return re.sub(r"[\x00-\x1f\x7f]", "?", value or "")[:512]


def junit_report(path: Path, root: Path = REPO_ROOT) -> dict:
    raw = path.read_bytes()
    if len(raw) > 8 * 1024 * 1024:
        raise ValueError("JUnit report exceeds the 8 MiB evidence bound")
    if re.search(br"<!\s*(?:DOCTYPE|ENTITY)\b", raw, re.IGNORECASE):
        raise ValueError("DTD/entities are not allowed in JUnit evidence")
    document = ET.fromstring(raw)
    # Namespace support does not permit silently ignoring every test case.
    for node in document.iter():
        node.tag = node.tag.rsplit("}", 1)[-1]
    suites = []
    for suite in document.iter("testsuite"):
        cases = []
        for case in suite.findall("testcase"):
            state = "error" if case.find("error") is not None else \
                "failure" if case.find("failure") is not None else \
                "skipped" if case.find("skipped") is not None else "passed"
            cases.append({"class": label(case.get("classname")), "name": label(case.get("name")), "state": state})
        # An aggregate testsuite is not a second copy of its child suites.
        if cases or not suite.findall("testsuite"):
            declared = {}
            for key in ("tests", "failures", "errors", "skipped"):
                value = suite.get(key)
                declared[key] = int(value) if value is not None and re.fullmatch(r"\d+", value) else None
            suites.append({"name": label(suite.get("name")), "declared_counts": declared, "cases": cases})
    if not suites:
        raise ValueError("XML contains no JUnit suites")
    cases = [case for suite in suites for case in suite["cases"]]
    counts = {"tests": len(cases), "passed": 0, "failure": 0, "error": 0, "skipped": 0}
    for case in cases:
        counts[case["state"]] += 1
    inconsistencies = []
    for suite in suites:
        for key, state in (("tests", None), ("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
            observed = len(suite["cases"]) if state is None else sum(c["state"] == state for c in suite["cases"])
            declared = suite["declared_counts"][key]
            if declared is not None and declared != observed:
                inconsistencies.append(f"Suite {suite['name']}: declared {key}={declared}, actual cases={observed}")
    return {"path": path.relative_to(root).as_posix(), "digest": digest_bytes(raw), "counts": counts,
            "suites": suites, "inconsistencies": inconsistencies,
            "source_and_stdout_omitted": True}


def artifact(path: Path, root: Path = REPO_ROOT) -> dict:
    if path.is_symlink():
        raise ValueError("Top-level artifact must not be a symlink")
    relative = path.relative_to(root).as_posix()
    if path.is_file():
        return {"path": relative, "kind": "file", "size": path.stat().st_size, "digest": digest_file(path)}
    if not path.is_dir():
        raise ValueError("Artifact is not a file/directory")
    manifest = []
    for entry in sorted(path.rglob("*")):
        name = entry.relative_to(path).as_posix()
        if entry.is_symlink():
            target = os.fsencode(os.readlink(entry))
            manifest.append({"path": name, "kind": "symlink", "size": len(target), "digest": digest_bytes(target)})
        elif entry.is_file():
            manifest.append({"path": name, "kind": "file", "size": entry.stat().st_size, "digest": digest_file(entry)})
    if not manifest:
        raise ValueError("Artifact directory has no files")
    return {"path": relative, "kind": "directory", "file_count": len(manifest),
            "digest": canonical_digest(manifest), "files": manifest}


def expand(pattern: str, root: Path = REPO_ROOT) -> list[Path]:
    # A drive-less /private path is not WindowsPath.is_absolute(), and a Windows
    # drive/UNC path is not PosixPath.is_absolute(). Validate the wire grammar
    # independently of the current host; never silently accept another flavor.
    if not isinstance(pattern, str) or not pattern or "\\" in pattern or ":" in pattern \
            or re.search(r"[\x00-\x1f\x7f]", pattern) or PurePosixPath(pattern).is_absolute() \
            or PureWindowsPath(pattern).drive or ".." in PurePosixPath(pattern).parts:
        raise ValueError("Evidence paths must be portable repository-relative globs, without traversal")
    paths = [Path(value) for value in glob.glob(str(root / pattern), recursive=True)]
    for path in paths:
        # Do not allow an intermediate symlink to expose files outside the checkout.
        if not path.resolve().is_relative_to(root.resolve()):
            raise ValueError("Evidence path escapes the repository")
    return sorted(set(paths))


def collect(args: argparse.Namespace, root: Path = REPO_ROOT) -> dict:
    status = os.environ.get("KT_CI_JOB_STATUS", "unknown")
    if status not in {"success", "failure", "cancelled", "skipped", "unknown"}:
        status = "unknown"
    result = {"schema_version": 1, "evidence_kind": "preview_implementation_evidence",
              "recorded_at": utc_now(), "profile": args.profile, "job_status": status,
              "source": source_identity(root), "reports": [], "artifacts": [], "command_results": [],
              "missing_evidence": [], "errors": [],
              "qualification": "Exact executed suites/targets only; neither all catalog rules nor release qualification",
              "intermediate_patch_review": "not established by final-commit CI",
              "actual_codex_host_observation": "not performed by this workflow"}
    if args.environment.is_file():
        result["environment"] = json.loads(args.environment.read_text(encoding="utf-8"))
        if result["environment"].get("state") != "passed":
            result["errors"].append("Observed environment preflight did not pass")
        environment_source = result["environment"].get("source", {})
        if environment_source.get("working_tree_digest") != result["source"].get("working_tree_digest"):
            result["errors"].append("Environment and reports do not bind the same actual source tree")
    else:
        result["missing_evidence"].append(str(args.environment))
    reports = set()
    for pattern in args.reports:
        reports.update(p for p in expand(pattern, root) if p.is_file() and p.suffix == ".xml")
    for path in sorted(reports):
        try:
            report = junit_report(path, root)
            result["reports"].append(report)
            result["errors"].extend(report["inconsistencies"])
        except (OSError, ValueError, ET.ParseError) as error:
            result["errors"].append(f"Invalid report {path.relative_to(root).as_posix()}: {type(error).__name__}")
    counts = {"tests": 0, "passed": 0, "failure": 0, "error": 0, "skipped": 0}
    for report in result["reports"]:
        for key in counts:
            counts[key] += report["counts"][key]
    result["counts"] = counts
    result["test_count_scope"] = "Test-case occurrences in retained reports, not unique rules or natural opportunities"
    if args.require_tests and not counts["passed"]:
        result["missing_evidence"].append("No actually passed JUnit test cases")
    if counts["failure"] or counts["error"]:
        result["errors"].append("JUnit reports contain failures/errors")
    for pattern in args.require_report:
        matched = set(expand(pattern, root))
        if not any(report["counts"]["passed"] and root / report["path"] in matched for report in result["reports"]):
            result["missing_evidence"].append(f"No passed test cases in required report context: {pattern}")
    artifacts = set()
    for pattern in args.artifact + args.require_artifact:
        matched = expand(pattern, root)
        artifacts.update(matched)
        if pattern in args.require_artifact and not matched:
            result["missing_evidence"].append(f"Required artifact absent: {pattern}")
    for path in sorted(artifacts):
        try:
            result["artifacts"].append(artifact(path, root))
        except (OSError, ValueError) as error:
            result["errors"].append(f"Invalid artifact {path.relative_to(root).as_posix()}: {type(error).__name__}")
    for path in args.command_result:
        if not path.is_file():
            result["missing_evidence"].append(str(path))
            continue
        value = json.loads(path.read_text(encoding="utf-8"))
        result["command_results"].append(value)
        if value.get("state") != "passed":
            result["errors"].append(f"Command/smoke evidence did not pass: {path.name}")
        if value.get("source") and value["source"].get("working_tree_digest") != result["source"].get("working_tree_digest"):
            result["errors"].append(f"Command evidence source differs from the tested tree: {path.name}")
    result["state"] = "passed" if status == "success" and not result["errors"] and not result["missing_evidence"] else "incomplete"
    return result


def summary(result: dict) -> str:
    counts = result.get("counts", {})
    commit = result.get("source", {}).get("commit", "unrecorded")
    # Do not interpolate project-controlled identifiers or messages as Markdown.
    return ("## ktChecker preview evidence\n\n"
            f"State: **{result['state']}**. Commit: `{commit}`.\n\n"
            f"JUnit test-case occurrences: {counts.get('tests', 0)}; passed: {counts.get('passed', 0)}; "
            f"failures/errors: {counts.get('failure', 0) + counts.get('error', 0)}; skipped: {counts.get('skipped', 0)}.\n\n"
            f"Hashed artifacts: {len(result.get('artifacts', []))}. "
            f"Missing evidence: {len(result.get('missing_evidence', []))}. "
            f"Evidence errors: {len(result.get('errors', []))}.\n\n"
            "This records only executed implementation suites and target controls. "
            "It does not validate all catalog rules, review every intermediate patch, "
            "establish real-world precision, or observe an authenticated Codex model request.\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--environment", type=Path, required=True)
    parser.add_argument("--reports", action="append", default=[])
    parser.add_argument("--require-report", action="append", default=[])
    parser.add_argument("--artifact", action="append", default=[])
    parser.add_argument("--require-artifact", action="append", default=[])
    parser.add_argument("--command-result", type=Path, action="append", default=[])
    parser.add_argument("--require-tests", action="store_true")
    args = parser.parse_args()
    try:
        result = collect(args)
    except (OSError, ValueError, ET.ParseError) as error:
        result = {"schema_version": 1, "evidence_kind": "preview_implementation_evidence", "state": "incomplete",
                  "errors": [type(error).__name__], "missing_evidence": [],
                  "qualification": "Evidence collection failed; no validation implied"}
    write_json(args.output, result)
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with Path(step_summary).open("a", encoding="utf-8") as handle:
            handle.write(summary(result))
    print(summary(result))
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())

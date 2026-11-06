#!/usr/bin/env python3
"""Execute pinned bridge installs/tests/audits, without a Codex or inference call."""

import argparse
import json
import os
from pathlib import Path
import platform
import re
import shutil
import tarfile
import xml.etree.ElementTree as ET

if __package__:
    from .ci_common import REPO_ROOT, digest_bytes, digest_file, read_bounded, run_logged, source_identity, utc_now, write_json
else:
    from ci_common import REPO_ROOT, digest_bytes, digest_file, read_bounded, run_logged, source_identity, utc_now, write_json


NODE_VERSION = "24.20.0"
PACKAGE_NAMES = {"mcp": "@ktchecker/mcp-preview", "reference-host": "@ktchecker/reference-host-preview"}
PACKAGE_VERSION = "0.1.0-preview.1"
PINS = {
    "mcp": {"dependencies": {"@modelcontextprotocol/server": "2.4.0", "zod": "4.6.7"},
            "devDependencies": {"@modelcontextprotocol/client": "2.4.0", "@types/node": "24.10.0", "typescript": "5.9.3"}},
    "reference-host": {"dependencies": {"@ktchecker/mcp-preview": "file:../mcp", "zod": "4.6.7"},
                       "devDependencies": {"@types/node": "24.10.0", "typescript": "5.9.3"}},
}
TEST_SUMMARY = re.compile(r"^(?:#|ℹ)\s+(tests|suites|pass|fail|cancelled|skipped|todo)\s+(\d+)\s*$", re.MULTILINE)
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
PUBLIC_TEST = re.compile(r"(?m)^\s*test\(\s*(['\"])([^'\"\\\r\n]{1,512})\1\s*,")
MAX_JUNIT_BYTES = 8 * 1024 * 1024
MAX_TERMINAL_EVENTS = 10000
MAX_FAILED_IDENTITIES = 64
MAX_PARENT_IDENTITIES = 64
FAILURE_CODES = {"private_state_platform_unqualified", "ERR_TEST_FAILURE", "ERR_ASSERTION", "ENOENT", "EACCES", "EPERM",
                 "private_path_unsupported", "private_path_alias_unsupported", "private_acl_unavailable", "unsafe_state_path",
                 "state_permissions", "private_parent_permissions", "state_directory_in_use", "private_operation_unsupported",
                 "native_windows_required", "private_helper_command_bound", "native_windows_control_setup_failed"}
FAILURE_TYPES = {"testCodeFailure", "cancelledByParent", "testTimeoutFailure", "subtestsFailed",
                 "testAborted", "uncaughtException", "unhandledRejection"}


def node_npm_argv() -> tuple[list[str], list[str]]:
    """Use node + npm's real JS entrypoint, never interpolate a Windows .cmd."""
    node = shutil.which("node")
    npm = shutil.which("npm")
    if not node or not npm:
        raise FileNotFoundError("The selected Node/npm runtime is unavailable")
    node_path = Path(node).resolve()
    candidates = (Path(npm).resolve(), node_path.parent / "node_modules/npm/bin/npm-cli.js",
                  node_path.parent.parent / "lib/node_modules/npm/bin/npm-cli.js")
    cli = next((path for path in candidates if path.name == "npm-cli.js" and path.is_file()), None)
    if cli is None:
        raise FileNotFoundError("Cannot locate npm's actual JS entrypoint beside the selected Node runtime")
    return [str(node_path)], [str(node_path), str(cli.resolve())]


def lock_profile(directory: Path, name: str) -> tuple[dict, list[str]]:
    errors = []
    package = json.loads(read_bounded(directory / "package.json", 1024 * 1024))
    lock = json.loads(read_bounded(directory / "package-lock.json", 8 * 1024 * 1024))
    if not isinstance(package, dict) or not isinstance(lock, dict):
        raise ValueError("Package/lock must be JSON objects")
    packages = lock.get("packages", {})
    if not isinstance(packages, dict):
        raise ValueError("Lockfile packages must be an object")
    locked_root = packages.get("", {})
    if lock.get("lockfileVersion") != 3 or not isinstance(locked_root, dict):
        errors.append("An actual npm v3 lockfile is required")
        locked_root = {}
    direct = []
    for scope, pins in PINS[name].items():
        if package.get(scope) != pins or locked_root.get(scope) != pins:
            errors.append(f"{name}: direct {scope} differ from the exact audited SDK/tool pins")
        for dependency, version in pins.items():
            row = packages.get("node_modules/" + dependency, {})
            if not isinstance(row, dict):
                row = {}
            if version.startswith("file:"):
                if row.get("link") is not True or row.get("resolved") != "../mcp":
                    errors.append("Host bridge must resolve the explicit local MCP package")
            elif row.get("version") != version or not isinstance(row.get("integrity"), str) or not row["integrity"].startswith("sha512-"):
                errors.append(f"{dependency}: exact resolved version/integrity is absent")
            direct.append({"name": dependency, "scope": scope, "version": row.get("version") or version,
                           "resolved": row.get("resolved"), "integrity": row.get("integrity"),
                           "declared_license": row.get("license"), "license_notice_review": "not_qualified"})
    pin = (directory / ".node-version").read_text(encoding="utf-8").strip()
    if pin != NODE_VERSION:
        errors.append("Package Node pin differs from the selected audited bridge runtime")
    return {"name": name, "package_digest": digest_file(directory / "package.json"),
            "lock_digest": digest_file(directory / "package-lock.json"), "direct_dependencies": direct}, errors


def test_counts(output: str) -> tuple[dict, list[str]]:
    observations = list(TEST_SUMMARY.finditer(ANSI.sub("", output)))
    counts = {match[1]: int(match[2]) for match in observations}
    keys = ("tests", "suites", "pass", "fail", "cancelled", "skipped", "todo")
    errors = []
    if len(observations) != len(counts):
        errors.append("npm test emitted duplicate/ambiguous Node summary counters")
    if any(key not in counts for key in keys):
        errors.append("npm test omitted the actual Node test-runner summary")
    elif counts["tests"] != sum(counts[key] for key in ("pass", "fail", "cancelled", "skipped", "todo")):
        errors.append("Node test summary case totals are inconsistent")
    if not counts.get("tests") or not counts.get("pass"):
        errors.append("No actual passed Node test-case occurrences")
    if counts.get("fail") or counts.get("cancelled"):
        errors.append("Node tests contain failures/cancellations")
    return counts, errors


def public_test_cases(name: str) -> dict[str, str]:
    """Only literal names from the public authored test entries may be uploaded."""
    known = {}
    ambiguous = set()
    for path in sorted((REPO_ROOT / "bridges" / name / "test").glob("*.test.ts")):
        if path.is_symlink() or not re.fullmatch(r"[A-Za-z0-9_.-]+\.test\.ts", path.name):
            raise ValueError("Public Node test entry path is unsupported")
        text = path.read_bytes()
        if len(text) > 1024 * 1024:
            raise ValueError("Public Node test entry exceeds its name-inventory bound")
        for match in PUBLIC_TEST.finditer(text.decode("utf-8")):
            label = match[2]
            if re.search(r"[\x00-\x1f\x7f]", label):
                continue
            if label in known:
                ambiguous.add(label)
            known[label] = path.relative_to(REPO_ROOT).as_posix()
    return {label: source for label, source in known.items() if label not in ambiguous}


def test_report_environment(environment: dict[str, str], report: Path) -> dict[str, str]:
    # Retain the ordinary summary, JUnit leaves and actual terminal occurrences.
    # JUnit drops a parent's own outcome when it has children. All raw reports
    # stay below unuploaded raw/. Quoting is NODE_OPTIONS syntax,
    # not a shell command; npm and tsc processes do not enable Node's test mode.
    destination = report.resolve().as_posix()
    if re.search(r"[\x00-\x1f\x7f]", destination):
        raise ValueError("Unsupported Node reporter destination")
    options = "--test-reporter=spec --test-reporter-destination=stdout --test-reporter=junit " \
        "--test-reporter-destination=" + json.dumps(destination, ensure_ascii=False) + \
        " --test-reporter=" + json.dumps((REPO_ROOT / "scripts/ci/node_test_events.mjs").resolve().as_uri(), ensure_ascii=False) + \
        " --test-reporter-destination=" + json.dumps(report.with_suffix(".events.jsonl").resolve().as_posix(), ensure_ascii=False)
    return {**environment, "NODE_OPTIONS": (environment.get("NODE_OPTIONS", "") + " " + options).strip()}


def _event_source(row: dict, public_cases: dict[str, str]) -> str | None:
    source = public_cases.get(row["name"])
    if source is None or row["file"] is None:
        return None
    source_path = Path(source)
    compiled = REPO_ROOT / source_path.parent.parent / "dist/test" / source_path.with_suffix(".js").name
    return source if Path(row["file"]) == compiled else None


def terminal_event_report(path: Path, document: ET.Element, node_counts: dict, public_cases: dict[str, str]) -> tuple[dict, dict, list[str]]:
    """Match ordered terminal occurrences to JUnit's tree, never infer parent success."""
    if path.is_symlink() or path.stat().st_size > MAX_JUNIT_BYTES:
        raise ValueError("Unsafe/oversized Node terminal-event report")
    with path.open("rb") as handle:
        raw = handle.read(MAX_JUNIT_BYTES + 1)
    lines = raw[:-1].split(b"\n") if raw.endswith(b"\n") else []
    if len(raw) > MAX_JUNIT_BYTES or not 2 <= len(lines) <= MAX_TERMINAL_EVENTS + 2 or any(len(line) + 1 > 65536 for line in lines):
        raise ValueError("Incomplete/oversized Node terminal-event report")

    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("Ambiguous Node terminal-event JSON")
            value[key] = item
        return value

    try:
        values = [json.loads(line, object_pairs_hook=unique) for line in lines]
    except RecursionError as error:
        raise ValueError("Overnested Node terminal-event JSON") from error
    rows = values[1:-1]
    if values[0] != {"schema": "ktchecker-node-terminal-events/1", "node_version": NODE_VERSION} or \
            not isinstance(values[-1], dict) or values[-1] != {"complete": True, "occurrences": len(rows)} or \
            values[-1]["complete"] is not True or type(values[-1]["occurrences"]) is not int:
        raise ValueError("Incomplete/incompatible Node terminal-event report")
    fields = {"ordinal", "name", "file", "nesting", "test_type", "event", "skip", "todo", "junit_skip", "junit_todo", "failure_type", "reason_codes"}
    for index, row in enumerate(rows, 1):
        if not isinstance(row, dict) or set(row) != fields or type(row["ordinal"]) is not int or row["ordinal"] != index or \
                not isinstance(row["name"], str) or len(row["name"].encode("utf-16-le", errors="surrogatepass")) > 4096 * 2 or \
                (row["file"] is not None and (not isinstance(row["file"], str) or len(row["file"].encode("utf-16-le", errors="surrogatepass")) > 8192 * 2)) or \
                type(row["nesting"]) is not int or not 0 <= row["nesting"] <= 128 or \
                row["test_type"] not in ("test", "suite") or row["event"] not in ("pass", "fail") or \
                any(type(row[key]) is not bool for key in ("skip", "todo", "junit_skip", "junit_todo")) or \
                (row["skip"] and row["todo"]) or (row["junit_skip"] and not row["skip"]) or (row["junit_todo"] and not row["todo"]) or \
                (row["failure_type"] is not None and (not isinstance(row["failure_type"], str) or row["failure_type"] not in FAILURE_TYPES)) or \
                not isinstance(row["reason_codes"], list) or any(not isinstance(code, str) or code not in FAILURE_CODES for code in row["reason_codes"]) or \
                row["reason_codes"] != sorted(set(row["reason_codes"])):
            raise ValueError("Malformed Node terminal occurrence")

    nodes = []

    def visit(node: ET.Element, nesting: int):
        if nesting > 128 or node.tag not in {"testcase", "testsuite"}:
            raise ValueError("Unsupported Node JUnit tree")
        children = [child for child in node if child.tag in {"testcase", "testsuite"}]
        if node.tag == "testsuite":
            if not children or len(children) != len(node):
                raise ValueError("Unsupported Node JUnit parent")
            failures = sum(bool(child.findall("failure")) or child.get("failures", "0") != "0" for child in children)
            skipped = sum(bool(child.findall("skipped")) or child.get("skipped", "0") != "0" for child in children)
            if any(node.get(key) != str(value) for key, value in {"tests": len(children), "failures": failures, "skipped": skipped, "errors": 0, "disabled": 0}.items()):
                raise ValueError("Node JUnit child rollup differs from its actual children")
            for child in children:
                visit(child, nesting + 1)
        elif any(child.tag not in {"skipped", "failure"} or len(child) for child in node):
            raise ValueError("Unsupported Node JUnit leaf content")
        nodes.append((node, nesting))
        if len(nodes) > MAX_TERMINAL_EVENTS:
            raise ValueError("Node JUnit occurrence bound exceeded")

    if document.tag != "testsuites":
        raise ValueError("Actual Node terminal matching requires its testsuites root")
    for child in document:
        visit(child, 0)
    if len(nodes) != len(rows):
        raise ValueError("Node terminal/JUnit occurrence identities differ")
    counts = {"tests": 0, "suites": 0, "pass": 0, "fail_or_cancelled": 0, "skipped": 0, "todo": 0}
    parents, by_node = [], {}
    unknown_parents = omitted_parents = failed_suites = leaf_tests = parent_tests = leaf_suites = parent_suites = 0
    for (node, nesting), row in zip(nodes, rows):
        if node.get("name") != row["name"] or nesting != row["nesting"]:
            raise ValueError("Node terminal/JUnit occurrence order or nesting differs")
        parent = node.tag == "testsuite"
        if not parent:
            directives = [item.get("type") for item in node.findall("skipped")]
            expected = (["skipped"] if row["junit_skip"] else []) + (["todo"] if row["junit_todo"] else [])
            if node.get("file") != row["file"] or len(node.findall("failure")) != int(row["event"] == "fail") or node.findall("error") or directives != expected:
                raise ValueError("Node terminal/JUnit leaf identity or outcome differs")
        state = "skipped" if row["skip"] else "todo" if row["todo"] else "fail_or_cancelled" if row["event"] == "fail" else "pass"
        if row["test_type"] == "suite":
            counts["suites"] += 1
            # Node's suite success check excludes TODO, but not SKIP. Its suite
            # outcomes are not part of the ordinary test pass/fail counters.
            failed_suites += int(row["event"] == "fail" and not row["todo"])
            parent_suites += int(parent)
            leaf_suites += int(not parent)
        else:
            counts["tests"] += 1
            counts[state] += 1
            parent_tests += int(parent)
            leaf_tests += int(not parent)
        by_node[id(node)] = row
        if parent:
            source = _event_source(row, public_cases)
            if source is None:
                unknown_parents += 1
            elif len(parents) < MAX_PARENT_IDENTITIES:
                parents.append({"occurrence": row["ordinal"], "nesting": nesting, "name": row["name"], "public_source_path": source,
                                "test_type": row["test_type"], "state": state, "terminal_event": row["event"],
                                "failure_types": [row["failure_type"]] if row["failure_type"] else [], "reason_codes": row["reason_codes"]})
            else:
                omitted_parents += 1
    errors = []
    if any(counts[key] != node_counts.get(key) for key in ("tests", "suites", "pass", "skipped", "todo")) or \
            counts["fail_or_cancelled"] != node_counts.get("fail", 0) + node_counts.get("cancelled", 0):
        errors.append("Actual Node terminal occurrences differ from the ordinary test summary")
    if failed_suites:
        errors.append("Actual Node terminal events contain failed suites")
    return {"report_digest": digest_bytes(raw), "counts": counts, "terminal_occurrences": len(rows), "leaf_test_occurrences": leaf_tests,
            "parent_test_occurrences": parent_tests, "leaf_suite_occurrences": leaf_suites, "parent_suite_occurrences": parent_suites,
            "public_parent_occurrences": parents, "unknown_parent_occurrences": unknown_parents,
            "bounded_omitted_public_parent_occurrences": omitted_parents,
            "failed_suite_occurrences": failed_suites, "matching": "ordered_postorder_name_file_nesting_and_leaf_outcome; duplicate occurrences retained",
            "parent_outcome_scope": "actual terminal event; JUnit parent attributes describe children only",
            "failure_count_scope": "terminal fail events excluding test directives; fail/cancelled split remains the ordinary Node summary",
            "raw_report_uploaded": False}, by_node, errors


def junit_failures(path: Path, public_cases: dict[str, str], node_counts: dict, events: Path | None = None) -> tuple[dict, list[str]]:
    """Bounded case identities/codes only; never publish failure bodies or streams."""
    if path.is_symlink() or path.stat().st_size > MAX_JUNIT_BYTES:
        raise ValueError("Unsafe/oversized Node JUnit report")
    with path.open("rb") as handle:
        raw = handle.read(MAX_JUNIT_BYTES + 1)
    if len(raw) > MAX_JUNIT_BYTES or re.search(br"<!\s*(?:DOCTYPE|ENTITY)\b", raw, re.IGNORECASE):
        raise ValueError("Unsafe/oversized Node JUnit report")
    document = ET.fromstring(raw)
    if document.tag not in {"testsuites", "testsuite"}:
        raise ValueError("Not a Node JUnit suite report")
    cases = list(document.iter("testcase"))
    if len(cases) > 10000:
        raise ValueError("Node JUnit case occurrences exceed their bound")
    terminal, by_node, errors = terminal_event_report(events, document, node_counts, public_cases) if events is not None else (None, {}, [])
    counts = {"tests": len(cases), "passed": 0, "failure": 0, "error": 0, "skipped": 0}
    failed = []
    unknown = 0
    for case in cases:
        problems = case.findall("failure") + case.findall("error")
        state = "failure" if case.find("failure") is not None else "error" if case.find("error") is not None \
            else "skipped" if case.find("skipped") is not None else "passed"
        counts[state] += 1
        if not problems:
            continue
        label = case.get("name")
        row = by_node.get(id(case))
        if label not in public_cases or (row is not None and _event_source(row, public_cases) is None):
            unknown += 1
            continue
        # Known error codes help identify a fail-closed privacy guard. Neither
        # arbitrary messages nor unknown test/class names enter this projection.
        detail = "\n".join(problem.get("message", "") + "\n" + "".join(problem.itertext()) for problem in problems)
        codes = sorted(code for code in FAILURE_CODES if re.search(r"(?<![A-Za-z0-9_])" + re.escape(code) + r"(?![A-Za-z0-9_])", detail))
        types = sorted({problem.get("type") for problem in problems if problem.get("type") in FAILURE_TYPES})
        failed.append({"name": label, "public_source_path": public_cases[label], "state": state,
                       "failure_types": types, "reason_codes": codes,
                       **({"occurrence": row["ordinal"], "nesting": row["nesting"], "test_type": row["test_type"], "terminal_event": row["event"]} if row is not None else {})})
    if terminal is not None:
        for node in document.iter("testsuite"):
            row = by_node[id(node)]
            if row["event"] != "fail":
                continue
            source = _event_source(row, public_cases)
            if source is None:
                unknown += 1
            else:
                failed.append({"name": row["name"], "public_source_path": source, "state": "failure", "occurrence": row["ordinal"],
                               "nesting": row["nesting"], "test_type": row["test_type"], "terminal_event": row["event"],
                               "failure_types": [row["failure_type"]] if row["failure_type"] else [], "reason_codes": row["reason_codes"]})
        failed.sort(key=lambda case: case["occurrence"])
    elif counts["tests"] != node_counts.get("tests") or counts["passed"] != node_counts.get("pass") or \
            counts["failure"] + counts["error"] != node_counts.get("fail", 0) + node_counts.get("cancelled", 0) or \
            counts["skipped"] != node_counts.get("skipped", 0) + node_counts.get("todo", 0):
        errors.append("Actual Node JUnit occurrences differ from the ordinary test summary")
    omitted = max(0, len(failed) - MAX_FAILED_IDENTITIES)
    failed = failed[:MAX_FAILED_IDENTITIES]
    return {"report_digest": digest_bytes(raw), "case_counts": counts, "failed_cases": failed,
            "unknown_failed_case_occurrences": unknown, "bounded_omitted_failed_case_occurrences": omitted,
            "failure_messages_stdout_stderr_and_unknown_names_omitted": True,
            "raw_report_uploaded": False, **({"terminal_events": terminal, "case_count_scope": "JUnit leaves, including empty suites; not all terminal test occurrences"} if terminal is not None else {})}, errors


def audit_summary(output: str) -> tuple[dict, list[str]]:
    value = json.loads(output)
    if not isinstance(value, dict):
        return {}, ["npm audit did not emit a JSON object"]
    metadata = value.get("metadata", {})
    counts = metadata.get("vulnerabilities", {}) if isinstance(metadata, dict) else {}
    keys = ("info", "low", "moderate", "high", "critical", "total")
    if not isinstance(counts, dict) or any(type(counts.get(key)) is not int or counts[key] < 0 for key in keys):
        return {}, ["npm audit omitted actual vulnerability counts (a registry/network error is not a clean audit)"]
    result = {"audit_report_version": value.get("auditReportVersion"), "vulnerabilities": {key: counts[key] for key in keys},
              "registry_findings_not_license_or_host_qualification": True}
    errors = []
    if "error" in value or counts["total"] != sum(counts[key] for key in keys[:-1]):
        errors.append("npm audit emitted an error or inconsistent vulnerability totals")
    if counts["total"]:
        errors.append("npm audit reports vulnerabilities; pinned dependencies are not a clean audited baseline")
    return result, errors


def allowed_package_file(path: object) -> bool:
    if not isinstance(path, str) or not path or "\\" in path or ":" in path:
        return False
    parts = path.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        return False
    return path in {"package.json", "README.md", "LICENSE", "LICENSE.md", "LICENSE.txt", "NOTICE", "NOTICE.txt", "THIRD_PARTY_NOTICES.md"} \
        or path.startswith("dist/src/") and path.endswith((".js", ".js.map", ".d.ts"))


def pack_manifest(output: str, name: str) -> tuple[dict, list[str]]:
    values = json.loads(output)
    if not isinstance(values, list) or len(values) != 1 or not isinstance(values[0], dict):
        return {}, ["npm pack did not emit exactly one actual package manifest"]
    value = values[0]
    expected_filename = PACKAGE_NAMES[name].removeprefix("@").replace("/", "-") + "-" + PACKAGE_VERSION + ".tgz"
    errors = []
    if value.get("name") != PACKAGE_NAMES[name] or value.get("version") != PACKAGE_VERSION or value.get("filename") != expected_filename:
        errors.append("npm package name/version/filename differs from the exact preview package pin")
    files = value.get("files")
    if not isinstance(files, list) or not files:
        return {}, errors + ["npm pack omitted actual packaged file entries"]
    rows = []
    for row in files:
        if not isinstance(row, dict) or not allowed_package_file(row.get("path")) or type(row.get("size")) is not int or row["size"] < 0:
            errors.append("npm pack includes unsupported/private/test/evidence files or malformed entries")
        else:
            rows.append({key: row.get(key) for key in ("path", "size", "mode")})
    paths = [row["path"] for row in rows]
    if len(paths) != len(set(paths)) or "package.json" not in paths or "dist/src/main.js" not in paths:
        errors.append("npm package file manifest has duplicates or missing built entrypoints")
    if value.get("bundled") != []:
        errors.append("Preview npm packages must not silently bundle third-party dependencies")
    return {"name": value.get("name"), "version": value.get("version"), "filename": value.get("filename"),
            "integrity": value.get("integrity"), "size": value.get("size"), "files": rows,
            "unsigned": True, "publication": "not_performed", "release_qualified": False,
            "local_mcp_link_still_required": name == "reference-host",
            "owner_license_and_redistribution_review": "unresolved_release_blocker"}, errors


def verify_tarball(path: Path, name: str, dry_run: dict) -> tuple[dict, list[str]]:
    errors = []
    files = []
    package = None
    total = 0
    with tarfile.open(path, "r:gz") as archive:
        for entry in archive:
            if entry.isdir():
                continue
            relative = entry.name.removeprefix("package/")
            if not entry.name.startswith("package/") or not entry.isfile() or not allowed_package_file(relative):
                raise ValueError("Unsafe/unsupported npm tar entry; links and private/evidence files are not allowed")
            total += entry.size
            if entry.size < 0 or entry.size > 8 * 1024 * 1024 or total > 32 * 1024 * 1024:
                raise ValueError("npm tarball exceeds the preview byte bound")
            handle = archive.extractfile(entry)
            if handle is None:
                raise ValueError("Cannot read actual npm package bytes")
            raw = handle.read(entry.size + 1)
            if len(raw) != entry.size:
                raise ValueError("Actual npm tar entry size is inconsistent")
            if relative == "package.json":
                package = json.loads(raw)
            files.append({"path": relative, "size": len(raw), "digest": digest_bytes(raw)})
    if {row["path"]: row["size"] for row in files} != {row["path"]: row["size"] for row in dry_run.get("files", [])} or len({row["path"] for row in files}) != len(files):
        errors.append("Actual npm tar contents differ from the validated dry-run manifest")
    if not isinstance(package, dict) or package.get("name") != PACKAGE_NAMES[name] or package.get("version") != PACKAGE_VERSION \
            or package.get("private") is not True or package.get("dependencies") != PINS[name]["dependencies"]:
        errors.append("Actual packaged manifest name/version/dependencies/private status differ from the audited pins")
    return {"filename": path.name, "size": path.stat().st_size, "digest": digest_file(path), "files": files,
            "unsigned": True, "publication": "not_performed", "release_qualified": False}, errors


def execute(args: argparse.Namespace) -> dict:
    node, npm = node_npm_argv()
    result = {"schema_version": 1, "evidence_kind": "executed_bridge_controls", "state": "running",
              "started_at": utc_now(), "source": source_identity(), "packages": [], "stages": [], "errors": [],
              "runner": {key: os.environ[key] for key in ("RUNNER_OS", "RUNNER_ARCH", "ImageOS", "ImageVersion") if key in os.environ},
              "host": {"os": platform.system(), "arch": platform.machine()},
              "actual_codex_host_observation": "not_performed; host tests use a synthetic app-server",
              "mcp_scope": "Actual pinned official SDK protocol execution against a synthetic application broker",
              "jvm_broker_compiler_integration": "not_performed", "release_qualified": False,
              "windows_private_state_privacy": "unqualified; runtime must fail closed until native ACL validation exists"}
    write_json(args.output, result)
    environment = dict(os.environ, NO_COLOR="1", FORCE_COLOR="0", npm_config_fund="false", npm_config_update_notifier="false")

    def stage(name: str, argv: list[str], cwd: Path, mode: str | None = None) -> dict:
        stage_environment = environment
        timeout = args.reference_host_test_timeout if name == "reference-host-test" and mode == "test" else args.timeout
        if mode == "test":
            report = args.output.parent / "raw" / (name + ".junit.xml")
            events = report.with_suffix(".events.jsonl")
            report.parent.mkdir(parents=True, exist_ok=True)
            report.unlink(missing_ok=True)
            events.unlink(missing_ok=True)
            stage_environment = test_report_environment(environment, report)
        value, stdout, _ = run_logged(argv, cwd=cwd, log_base=args.output.parent / "raw" / name,
                                     timeout=timeout, env=stage_environment, separate_stderr=True,
                                     additional_output_paths=(report, events) if mode == "test" else ())
        value["timeout_seconds"] = timeout
        errors = []
        if value["state"] != "completed" or value["return_code"] != 0:
            errors.append("Actual npm/Node command did not complete successfully")
        if mode == "test":
            value["test_counts"], test_errors = test_counts(stdout)
            errors.extend(test_errors)
            try:
                value["test_failures"], report_errors = junit_failures(
                    report, public_test_cases(name.removesuffix("-test")), value["test_counts"], events)
                errors.extend(report_errors)
            except (OSError, ValueError, ET.ParseError):
                errors.append("Actual Node JUnit/terminal-event reports are absent/unsafe/malformed")
            value["test_count_scope"] = "Actual Node terminal test occurrences, including parents; JUnit leaves reported separately; not qualified rules or authenticated model observations"
        elif mode == "audit":
            try:
                value["audit"], audit_errors = audit_summary(stdout)
                errors.extend(audit_errors)
            except (ValueError, TypeError):
                errors.append("Actual npm audit JSON is absent/malformed")
        elif mode == "node-version":
            observed = stdout.strip()
            value["observed_node_version"] = observed
            if observed != "v" + NODE_VERSION:
                errors.append(f"Observed Node does not match exact {NODE_VERSION} pin")
        elif mode == "npm-version":
            value["observed_npm_version"] = stdout.strip() if re.fullmatch(r"\d+\.\d+\.\d+\s*", stdout) else None
            if not value["observed_npm_version"]:
                errors.append("Actual selected npm version was not observed")
        elif mode and mode.startswith("pack-"):
            try:
                value["package"], package_errors = pack_manifest(stdout, name.split("-pack-", 1)[0])
                errors.extend(package_errors)
            except (ValueError, TypeError):
                errors.append("Actual npm pack JSON is absent/malformed")
        value.update(name=name, state="passed" if not errors else "failed", errors=errors)
        result["stages"].append(value)
        result["errors"].extend(f"{name}: {error}" for error in errors)
        write_json(args.output, result)
        print(f"Bridge stage {name}: {value['state']}", flush=True)
        if mode == "test":
            for failed in value.get("test_failures", {}).get("failed_cases", []):
                print(f"Public Node test failed: {failed['public_source_path']}: {failed['name']}"
                      + (" [" + ", ".join(failed["reason_codes"]) + "]" if failed["reason_codes"] else ""), flush=True)
        return value

    stage("node-version", node + ["--version"], REPO_ROOT, "node-version")
    stage("npm-version", npm + ["--version"], REPO_ROOT, "npm-version")
    for name in PINS:
        package, errors = lock_profile(REPO_ROOT / "bridges" / name, name)
        result["packages"].append(package)
        result["errors"].extend(errors)
    if not result["errors"]:
        # Local linked host dependency needs MCP installed first. Installation
        # scripts are unnecessary for these pure-JS SDKs and not run in CI.
        for name in PINS:
            stage(name + "-ci", npm + ["ci", "--ignore-scripts", "--no-audit", "--fund=false"], REPO_ROOT / "bridges" / name)
        for name in PINS:
            stage(name + "-test", npm + ["test"], REPO_ROOT / "bridges" / name, "test")
        for name in PINS:
            stage(name + "-audit-all", npm + ["audit", "--json", "--audit-level=low"], REPO_ROOT / "bridges" / name, "audit")
            stage(name + "-audit-production", npm + ["audit", "--omit=dev", "--json", "--audit-level=low"], REPO_ROOT / "bridges" / name, "audit")
        if not result["errors"]:
            destination = REPO_ROOT / "build/ci-packages"
            destination.mkdir(parents=True, exist_ok=True)
            for name in PINS:
                cwd = REPO_ROOT / "bridges" / name
                dry = stage(name + "-pack-dry", npm + ["pack", "--dry-run", "--ignore-scripts", "--json"], cwd, "pack-dry")
                if dry["state"] != "passed":
                    continue
                path = destination / dry["package"]["filename"]
                path.unlink(missing_ok=True)
                packed = stage(name + "-pack-actual", npm + ["pack", "--ignore-scripts", "--json", "--pack-destination", str(destination)], cwd, "pack-actual")
                if packed["state"] == "passed":
                    try:
                        packed["tarball"], errors = verify_tarball(path, name, dry["package"])
                        packed["errors"].extend(errors)
                    except (OSError, ValueError, tarfile.TarError):
                        packed["errors"].append("Actual npm tarball is missing/unsafe/inconsistent")
                    if packed["errors"]:
                        packed["state"] = "failed"
                        result["errors"].extend(f"{name}-pack-actual: {error}" for error in packed["errors"])
    after = source_identity()
    result["source_after_execution"] = after
    if after["working_tree_digest"] != result["source"]["working_tree_digest"]:
        result["errors"].append("Bridge source/lock inputs changed during execution")
    result.update(state="passed" if not result["errors"] else "failed", finished_at=utc_now())
    write_json(args.output, result)
    return result


def finalize(path: Path) -> dict:
    try:
        value = json.loads(read_bounded(path, 8 * 1024 * 1024))
        if not isinstance(value, dict):
            raise ValueError("Bridge evidence must be a JSON object")
    except (OSError, ValueError):
        value = {"schema_version": 1, "evidence_kind": "executed_bridge_controls", "state": "incomplete",
                 "errors": ["Actual bridge command evidence is missing/malformed"], "stages": [], "release_qualified": False}
    status = os.environ.get("KT_CI_JOB_STATUS", "unknown")
    if status not in {"success", "failure", "cancelled", "skipped", "unknown"}:
        status = "unknown"
    value["job_status"] = status
    if status != "success":
        value["errors"].append("Hosted bridge job did not succeed")
    if value.get("source", {}).get("working_tree_digest") != source_identity()["working_tree_digest"]:
        value["errors"].append("Bridge evidence does not bind the final actual source/lock tree")
    if value.get("state") != "passed" or value["errors"]:
        value["state"] = "incomplete"
    value["finalized_at"] = utc_now()
    write_json(path, value)
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=300, help="Bound for commands other than the reference-host test suite")
    parser.add_argument("--reference-host-test-timeout", type=int, default=600,
                        help="Narrow bound for the full reference-host suite; all other command bounds are unchanged")
    parser.add_argument("--finalize", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 1200:
        parser.error("timeout must be 1..1200 seconds per bounded npm command")
    if not 1 <= args.reference_host_test_timeout <= 1200:
        parser.error("reference-host-test-timeout must be 1..1200 seconds")
    try:
        result = finalize(args.output) if args.finalize else execute(args)
    except (OSError, ValueError, TypeError) as error:
        try:
            result = json.loads(read_bounded(args.output, 8 * 1024 * 1024))
        except (OSError, ValueError):
            result = {"schema_version": 1, "evidence_kind": "executed_bridge_controls", "stages": [], "errors": [], "release_qualified": False}
        result["state"] = "failed"
        result["errors"].append(f"Bridge harness failed: {type(error).__name__}")
        write_json(args.output, result)
    counts = [row.get("test_counts", {}) for row in result.get("stages", [])]
    print(f"Bridge evidence: {result['state']}; Node test-case occurrences: {sum(row.get('tests', 0) for row in counts)}")
    for error in result["errors"]:
        print(error)
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())

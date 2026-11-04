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
    counts = {match[1]: int(match[2]) for match in TEST_SUMMARY.finditer(ANSI.sub("", output))}
    keys = ("tests", "suites", "pass", "fail", "cancelled", "skipped", "todo")
    errors = []
    if any(key not in counts for key in keys):
        errors.append("npm test omitted the actual Node test-runner summary")
    elif counts["tests"] != sum(counts[key] for key in ("pass", "fail", "cancelled", "skipped", "todo")):
        errors.append("Node test summary case totals are inconsistent")
    if not counts.get("tests") or not counts.get("pass"):
        errors.append("No actual passed Node test-case occurrences")
    if counts.get("fail") or counts.get("cancelled"):
        errors.append("Node tests contain failures/cancellations")
    return counts, errors


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
        value, stdout, _ = run_logged(argv, cwd=cwd, log_base=args.output.parent / "raw" / name,
                                     timeout=args.timeout, env=environment, separate_stderr=True)
        errors = []
        if value["state"] != "completed" or value["return_code"] != 0:
            errors.append("Actual npm/Node command did not complete successfully")
        if mode == "test":
            value["test_counts"], test_errors = test_counts(stdout)
            errors.extend(test_errors)
            value["test_count_scope"] = "Actual Node test-case occurrences; not qualified rules or authenticated model observations"
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
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--finalize", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 1200:
        parser.error("timeout must be 1..1200 seconds per bounded npm command")
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

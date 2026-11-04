#!/usr/bin/env python3
"""Provision pinned optional libraries and verify their exact OS-scoped JUnit controls.

A successful Windows policy check means portability tests ran with four visible
opt-out skips. It never means Detekt/Ktlint were executed on an unsupported host.
Raw resolver output and fixture receipts remain in the unuploaded raw directory.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import sys
import xml.etree.ElementTree as ET

if __package__:
    from .ci_common import REPO_ROOT, digest_bytes, digest_file, run_logged, source_identity, utc_now, write_json
    from .collect_evidence import junit_report
else:
    from ci_common import REPO_ROOT, digest_bytes, digest_file, run_logged, source_identity, utc_now, write_json
    from collect_evidence import junit_report


DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
PROVIDERS = {
    "detekt": {
        "artifact_count": 5,
        "class": "dev.ktchecker.providers.detekt.DetektIntegrationTest",
        "cases": {
            "positive negative settings version suppression and immutable edit regression execute upstream()":
                "Real Detekt integration disabled; use -PdetektIntegration=true",
            "full resolved coroutine upstream guidance never implements the narrower catalog contract()":
                "Real semantic Detekt integration disabled; use -PdetektIntegration=true",
        },
        "scope": "synthetic syntax controls and resolved coroutine upstream guidance; no custom cancellation contract qualification",
    },
    "ktlint": {
        "artifact_count": 3,
        "class": "dev.ktchecker.providers.ktlint.KtlintIntegrationTest",
        "cases": {
            "four selected syntax rules execute positive negative config version scoped suppression and immutable regression()":
                "Real Ktlint integration disabled; use -PktlintIntegration=true",
            "real editorconfig inheritance exemptions and parse errors never produce clean semantic coverage()":
                "Real configuration integration disabled; use -PktlintIntegration=true",
        },
        "scope": "four selected syntax rules and configuration/parse controls; embedded Kotlin 2.2.21 is not harness Kotlin 2.4.21",
    },
}
SCOPES = {"supported": "real_optional_provider_fixture_controls", "windows-portability": "windows_portability_only"}
UNAVAILABLE = "process_group_containment_unavailable"


def unique_pairs(pairs: list) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result


def strict_json(raw: bytes, limit: int = 2 * 1024 * 1024) -> dict:
    if not raw or len(raw) > limit:
        raise ValueError("absent/oversized JSON")
    def invalid_constant(_):
        raise ValueError("nonfinite JSON value")
    try:
        result = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_pairs, parse_constant=invalid_constant)
    except RecursionError as error:
        raise ValueError("JSON nesting exceeds the parser bound") from error
    if not isinstance(result, dict):
        raise ValueError("JSON object required")
    return result


def root_relative(path: Path, root: Path) -> Path:
    """Permit the trusted root's outer alias, never traversal or descendant aliases."""
    absolute = path.absolute()
    try:
        relative = absolute.relative_to(root.absolute())
    except ValueError:
        relative = absolute.relative_to(root.resolve())
    if any(part in {".", ".."} for part in relative.parts):
        raise ValueError("evidence paths must not contain traversal")
    return relative


def regular_path(path: Path, root: Path) -> Path:
    relative = root_relative(path, root)
    current = root.resolve()
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            raise ValueError("evidence input must not use a descendant symlink")
    if not current.is_file():
        raise ValueError("evidence input must be a regular file")
    return current


def regular_input(path: Path, root: Path) -> bytes:
    current = regular_path(path, root)
    if current.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("absent/nonregular/oversized evidence input")
    with current.open("rb") as stream:
        raw = stream.read(8 * 1024 * 1024 + 1)
    if len(raw) > 8 * 1024 * 1024:
        raise ValueError("evidence input grew beyond its byte bound")
    return raw


def host_scope(profile: str, system: str) -> None:
    if profile not in SCOPES or (profile == "windows-portability") != (system == "Windows") \
            or system not in {"Linux", "Darwin", "Windows"}:
        raise ValueError("configured optional-provider scope differs from the actual host")


def environment_input(path: Path, root: Path, source: dict, system: str) -> dict:
    value = strict_json(regular_input(path, root))
    if value.get("state") != "passed" or value.get("source", {}).get("working_tree_digest") != source["working_tree_digest"] \
            or value.get("host", {}).get("os") != system or type(value.get("java", {}).get("major")) is not int \
            or value["java"]["major"] != 21:
        raise ValueError("library prerequisites require the matching observed JVM-21 host")
    return value


def manifest(provider: str, root: Path) -> tuple[list[dict], str]:
    raw = regular_input(root / f"modules/provider-{provider}/src/main/resources/{provider}-artifacts.json", root)
    value = strict_json(raw)
    pins = value.get("artifacts")
    if type(value.get("schema_version")) is not int or value["schema_version"] != 1 \
            or not isinstance(pins, list) or len(pins) != PROVIDERS[provider]["artifact_count"]:
        raise ValueError("wrong pinned artifact manifest")
    for pin in pins:
        if not isinstance(pin, dict) or not isinstance(pin.get("filename"), str) \
                or not re.fullmatch(r"[A-Za-z0-9._-]+\.jar", pin["filename"]) \
                or not isinstance(pin.get("coordinate"), str) \
                or not re.fullmatch(r"[A-Za-z0-9._-]+:[A-Za-z0-9._-]+:[A-Za-z0-9._+-]+(?::[A-Za-z0-9._-]+)?", pin["coordinate"]) \
                or type(pin.get("size")) is not int or not 0 < pin["size"] <= 200_000_000 \
                or not isinstance(pin.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", pin["sha256"]) \
                or pin.get("upstream_checksum_algorithm") != "sha1" \
                or not isinstance(pin.get("upstream_checksum"), str) or not re.fullmatch(r"[0-9a-f]{40}", pin["upstream_checksum"]):
            raise ValueError("invalid pinned artifact identity")
    if len({pin["coordinate"] for pin in pins}) != len(pins) or len({pin["filename"] for pin in pins}) != len(pins):
        raise ValueError("duplicate pinned artifact identity")
    return pins, digest_bytes(raw)


def resolver_projection(provider: str, stdout: str, pins: list[dict], root: Path) -> list[dict]:
    value = strict_json(stdout.encode("utf-8"))
    rows = value.get("artifacts")
    if type(value.get("schema_version")) is not int or value["schema_version"] != 1 \
            or not isinstance(rows, list) or len(rows) != len(pins):
        raise ValueError("resolver identities absent")
    expected = {pin["coordinate"]: pin for pin in pins}
    seen = set()
    projected = []
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("coordinate"), str) \
                or row["coordinate"] not in expected or row["coordinate"] in seen:
            raise ValueError("unrecognized/duplicate resolver identity")
        pin = expected[row["coordinate"]]
        path = root / ".plan-deps" / provider / pin["filename"]
        if row.get("digest") != "sha256:" + pin["sha256"] or row.get("path") != str(path.absolute()):
            raise ValueError("resolver did not bind the exact pinned cache file")
        # Independently inspect cached bytes after the genuine official resolver.
        verified_path = regular_path(path, root)
        if verified_path.stat().st_size != pin["size"] or digest_file(verified_path) != row["digest"]:
            raise ValueError("resolved artifact bytes differ from the pin")
        sha1 = hashlib.sha1()
        with verified_path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                sha1.update(chunk)
        if sha1.hexdigest() != pin["upstream_checksum"]:
            raise ValueError("resolved official SHA-1 identity differs")
        seen.add(row["coordinate"])
        projected.append({"coordinate": row["coordinate"], "digest": row["digest"], "size": pin["size"],
                          "official_checksum_algorithm": "sha1", "official_checksum": pin["upstream_checksum"]})
    return sorted(projected, key=lambda row: row["coordinate"])


def safe_command(result: dict) -> dict:
    return {key: result[key] for key in ("state", "return_code", "started_at", "elapsed_seconds", "output_bytes",
                                        "stdout_digest", "stderr_digest", "raw_logs_uploaded") if key in result}


def base_result(profile: str, source: dict, system: str, phase: str) -> dict:
    return {"schema_version": 1, "evidence_kind": f"optional_library_provider_{phase}", "recorded_at": utc_now(),
            "state": "failed", "source": source, "host_os": system, "profile": profile,
            "execution_scope": SCOPES.get(profile, "invalid"), "providers": [], "errors": [],
            "provider_controls_executed": False, "tuple_qualified": False, "release_qualified": False,
            "raw_logs_uploaded": False,
            "qualification": "Only exact observed fixture controls; Windows portability success is not optional-provider execution"}


def provision(args: argparse.Namespace, root: Path = REPO_ROOT) -> dict:
    system, source = platform.system(), source_identity(root)
    result = base_result(args.profile, source, system, "prerequisites")
    try:
        host_scope(args.profile, system)
        environment_input(args.environment, root, source, system)
        if args.profile == "supported" and system == "Darwin":
            # The bundled C helper has a separate build preflight. The replaced
            # setsid/perl/kill launcher chain is not an optional-library input.
            java_home = Path(os.environ.get("JAVA_HOME", ""))
            if not (java_home / "lib/server/libjvm.dylib").is_file():
                raise ValueError("supported JDK runtime input unavailable")
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        result["errors"].append("optional_provider_host_preflight_failed")
        return result
    for provider in PROVIDERS:
        row = {"provider_id": provider, "integration_opt_in": args.profile == "supported",
               "fixture_scope": PROVIDERS[provider]["scope"], "expected_integration_methods": 2,
               "expected_skipped_methods": 2 if args.profile == "windows-portability" else 0,
               "controls_executed": False, "qualified": False, "artifacts": []}
        if args.profile == "windows-portability":
            row.update(availability="unavailable", reason=UNAVAILABLE, resolver_executed=False)
        else:
            try:
                pins, pin_digest = manifest(provider, root)
                command, stdout, _ = run_logged(
                    [sys.executable, str(root / f"providers/{provider}-fixtures/resolve_artifacts.py"), "--download"],
                    cwd=root, log_base=args.output.parent / "raw" / f"{provider}-resolution",
                    timeout=args.timeout, max_output_bytes=1024 * 1024,
                )
                row.update(resolver_executed=command.get("state") != "unavailable", resolver=safe_command(command),
                           manifest_digest=pin_digest)
                if command.get("state") != "completed" or command.get("return_code") != 0:
                    raise ValueError("pinned resolver failed")
                row["artifacts"] = resolver_projection(provider, stdout, pins, root)
                row.update(availability="available", reason="pinned_prerequisites_verified_not_provider_execution")
            except (OSError, ValueError, KeyError, TypeError):
                row.update(availability="failed", reason="pinned_artifact_resolution_failed")
                result["errors"].append(f"{provider}_pinned_artifact_resolution_failed")
        result["providers"].append(row)
    if source_identity(root)["working_tree_digest"] != source["working_tree_digest"]:
        result["errors"].append("source_changed_during_library_provisioning")
    result["state"] = "passed" if not result["errors"] else "failed"
    return result


def integration_report(provider: str, root: Path, opt_in: bool) -> dict:
    definition = PROVIDERS[provider]
    path = root / f"modules/provider-{provider}/build/test-results/test/TEST-{definition['class']}.xml"
    raw = regular_input(path, root)
    report = junit_report(path, root)
    if report["inconsistencies"]:
        raise ValueError("inconsistent integration counts")
    # junit_report already rejects DTD/entities and oversized XML.
    document = ET.fromstring(raw)
    cases = []
    for node in document.iter():
        node.tag = node.tag.rsplit("}", 1)[-1]
    for case in document.iter("testcase"):
        if case.get("classname") != definition["class"] or case.get("name") not in definition["cases"]:
            raise ValueError("unrecognized integration test identity")
        if sum(len(case.findall(key)) for key in ("skipped", "failure", "error")) > 1:
            raise ValueError("ambiguous integration test state")
        skipped = case.find("skipped")
        if skipped is not None and (opt_in or skipped.get("type") != "org.opentest4j.TestAbortedException" \
                or skipped.get("message") != "org.opentest4j.TestAbortedException: Assumption failed: " + definition["cases"][case.get("name")]):
            raise ValueError("integration was skipped for an unexpected reason")
        cases.append(case.get("name"))
    if len(cases) != 2 or set(cases) != set(definition["cases"]):
        raise ValueError("missing/duplicate integration methods")
    expected = {"tests": 2, "passed": 2 if opt_in else 0, "failure": 0, "error": 0, "skipped": 0 if opt_in else 2}
    return {"path": report["path"], "digest": report["digest"], "counts": report["counts"],
            "cases": sorted((case for suite in report["suites"] for case in suite["cases"]), key=lambda case: case["name"]),
            "expected_scope_verified": report["counts"] == expected}


def collect_controls(args: argparse.Namespace, root: Path = REPO_ROOT) -> dict:
    system, source = platform.system(), source_identity(root)
    result = base_result(args.profile, source, system, "fixture_controls")
    prerequisites = {}
    build = {}
    try:
        host_scope(args.profile, system)
        prerequisites = strict_json(regular_input(args.prerequisites, root))
        build = strict_json(regular_input(args.build, root))
        if prerequisites.get("state") != "passed" or prerequisites.get("profile") != args.profile \
                or prerequisites.get("host_os") != system or prerequisites.get("execution_scope") != SCOPES[args.profile] \
                or any(value.get("source", {}).get("working_tree_digest") != source["working_tree_digest"] for value in (prerequisites, build)):
            raise ValueError("library control source/scope binding failed")
        rows = prerequisites.get("providers")
        if not isinstance(rows, list) or len(rows) != len(PROVIDERS) \
                or {row.get("provider_id") for row in rows if isinstance(row, dict)} != set(PROVIDERS):
            raise ValueError("library prerequisites incomplete")
        for row in rows:
            opt_in = args.profile == "supported"
            if row.get("integration_opt_in") is not opt_in or row.get("controls_executed") is not False \
                    or row.get("availability") != ("available" if opt_in else "unavailable") \
                    or (not opt_in and (row.get("reason") != UNAVAILABLE or row.get("resolver_executed") is not False)):
                raise ValueError("library prerequisites scope is ambiguous")
        result["prerequisites_digest"] = digest_file(args.prerequisites)
        result["host_build_digest"] = digest_file(args.build)
        if build.get("state") != "passed":
            result["errors"].append("host_build_did_not_pass")
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        result["errors"].append("library_controls_prerequisite_binding_failed")
    for provider in PROVIDERS:
        opt_in = args.profile == "supported"
        row = {"provider_id": provider, "integration_opt_in": opt_in, "fixture_scope": PROVIDERS[provider]["scope"],
               "state": "unavailable" if not opt_in else "failed", "controls_executed": False, "qualified": False,
               "expected_integration_methods": 2, "expected_skipped_methods": 0 if opt_in else 2,
               "reason": UNAVAILABLE if not opt_in else "fixture_control_gate_not_satisfied"}
        try:
            row["report"] = integration_report(provider, root, opt_in)
            task = f":provider-{provider}:test"
            row["task_path"] = task
            row["task_outcome"] = build.get("tasks", {}).get(task, "absent")
            if not row["report"]["expected_scope_verified"] or row["task_outcome"] != "EXECUTED":
                raise ValueError("integration method/task scope did not execute as required")
            if not result["errors"] and opt_in:
                row.update(state="executed", controls_executed=True, reason="exact_fixture_assertions_passed_unqualified")
        except (OSError, ValueError, KeyError, TypeError, AttributeError, ET.ParseError):
            result["errors"].append(f"{provider}_integration_method_scope_failed")
        result["providers"].append(row)
    result["provider_controls_executed"] = bool(result["providers"]) and all(row["controls_executed"] for row in result["providers"])
    result["counts"] = {key: sum(row.get("report", {}).get("counts", {}).get(key, 0) for row in result["providers"])
                        for key in ("tests", "passed", "failure", "error", "skipped")}
    result["state"] = "passed" if not result["errors"] else "failed"
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("provision", "collect"), required=True)
    parser.add_argument("--profile", choices=tuple(SCOPES), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--environment", type=Path, default=REPO_ROOT / "build/ci-evidence/environment.json")
    parser.add_argument("--prerequisites", type=Path, default=REPO_ROOT / "build/ci-evidence/library-prerequisites.json")
    parser.add_argument("--build", type=Path, default=REPO_ROOT / "build/ci-evidence/host-build.json")
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 900:
        parser.error("timeout must be between 1 and 900 seconds")
    result = provision(args) if args.phase == "provision" else collect_controls(args)
    write_json(args.output, result)
    print(f"Library {args.phase}: {result['state']}; scope={result['execution_scope']}; provider controls executed={result['provider_controls_executed']}")
    for row in result["providers"]:
        counts = row.get("report", {}).get("counts")
        print(f"{row['provider_id']}: {row.get('availability', row.get('state'))}; reason={row['reason']}; "
              f"observed integration counts={counts if counts is not None else 'not collected'}")
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())

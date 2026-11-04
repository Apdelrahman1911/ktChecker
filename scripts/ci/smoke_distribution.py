#!/usr/bin/env python3
"""Execute the installed preview CLI, including paths with spaces and Unicode."""

import argparse
import json
import os
from pathlib import Path
import platform
import re
import shutil
import tempfile

if __package__:
    from .ci_common import REPO_ROOT, canonical_digest, digest_file, run_logged, source_identity, write_json
else:
    from ci_common import REPO_ROOT, canonical_digest, digest_file, run_logged, source_identity, write_json


SUPERVISOR_PROTOCOL = "ktchecker-owned-child-supervisor/preview-1"
SUPERVISOR_TARGETS = {"linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64"}
CONTAINMENT_SCOPE = "Host-specific cooperative owned-child cleanup; not a sandbox or a Windows Job Objects implementation"
CONTAINMENT_REASONS = {"process_group_containment_unavailable", "process_supervisor_bundle_unavailable",
                       "process_supervisor_bundle_origin_unavailable", "process_supervisor_manifest_invalid",
                       "process_supervisor_binary_invalid", "process_supervisor_binary_digest_mismatch",
                       "process_supervisor_bundle_changed", "process_supervisor_resource_unavailable",
                       "process_supervisor_private_io_unavailable", "process_supervisor_image_changed"}


def supervisor_target(system: str, architecture: str) -> str:
    cpu = {"amd64": "x86_64", "x86_64": "x86_64", "aarch64": "aarch64", "arm64": "aarch64"}.get(architecture.lower())
    os_name = system.lower()
    host = "windows" if os_name.startswith("windows") else "linux" if os_name == "linux" else \
        "macos" if os_name in {"darwin", "mac os x", "macos"} else None
    return f"{host}-{cpu}" if host and cpu else "unsupported"


def validate_containment(value: object, expected_target: str | None = None) -> list[str]:
    if not isinstance(value, dict):
        return ["doctor omitted the installed process-containment inspection"]
    required = {"state", "target", "bundledBytesVerified", "executionVerified", "releaseQualified", "reasons", "scope"}
    supported = value.get("state") == "available"
    expected_keys = required | ({"identity"} if supported else set())
    target = value.get("target")
    if set(value) != expected_keys or value.get("state") not in ("available", "unavailable") \
            or not isinstance(target, str) or target not in SUPERVISOR_TARGETS | {"windows-x86_64", "windows-aarch64", "unsupported"} \
            or value.get("bundledBytesVerified") is not supported or value.get("executionVerified") is not False \
            or value.get("releaseQualified") is not False or value.get("scope") != CONTAINMENT_SCOPE:
        return ["doctor process-containment metadata or qualification is invalid"]
    reasons = value.get("reasons")
    if not isinstance(reasons, list) or len(reasons) > 8 or any(not isinstance(code, str) or code not in CONTAINMENT_REASONS for code in reasons) \
            or supported and reasons or not supported and not reasons:
        return ["doctor process-containment reasons are invalid"]
    if expected_target is not None and (target != expected_target or supported != (expected_target in SUPERVISOR_TARGETS)):
        return ["doctor process-containment bytes do not match the actual selected host"]
    if supported:
        identity = value.get("identity")
        if target not in SUPERVISOR_TARGETS or not isinstance(identity, dict) \
                or set(identity) != {"protocolVersion", "target", "sourceDigest", "binaryDigest"} \
                or identity.get("protocolVersion") != SUPERVISOR_PROTOCOL or identity.get("target") != target \
                or any(not isinstance(identity.get(key), str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", identity[key])
                       for key in ("sourceDigest", "binaryDigest")):
            return ["doctor process-containment bundle identity is invalid"]
    return []


def windows_batch_command(launcher: Path, comspec: str) -> str:
    """cmd /s strips the outer quote pair; the inner pair protects the batch path.

    Only the three fixed doctor arguments are accepted here. Reject cmd expansion
    characters in paths instead of interpolating project-controlled command text.
    /v:off disables delayed expansion and /d disables AutoRun registry commands.
    """
    for value in (str(launcher), comspec):
        if any(character in value for character in '"%!^&|<>\r\n'):
            raise ValueError("Unsafe cmd expansion character in batch launcher path")
    return f'"{comspec}" /d /v:off /s /c ""{launcher}" doctor --format json"'


def validate_doctor(value: object, *, require_containment: bool = False, expected_target: str | None = None) -> list[str]:
    if not isinstance(value, dict):
        return ["doctor did not return a JSON object"]
    errors = []
    expected = {"contractVersion": "ktchecker/preview-1", "command": "doctor",
                "status": "preview", "releaseQualified": False}
    for key, expected_value in expected.items():
        if type(value.get(key)) is not type(expected_value) or value.get(key) != expected_value:
            errors.append(f"Unexpected doctor field: {key}")
    if not isinstance(value.get("version"), str) or not value["version"].strip():
        errors.append("doctor omitted its distribution version")
    runtime = value.get("runtime")
    if not isinstance(runtime, dict) or any(
        not isinstance(runtime.get(key), str) or not runtime[key] for key in ("javaVersion", "os", "arch")
    ):
        errors.append("doctor omitted observed runtime identity")
    for key in ("capabilities", "gaps"):
        if not isinstance(value.get(key), list):
            errors.append(f"doctor omitted explicit {key}")
    if "installedProvidersInspected" in value and value["installedProvidersInspected"] is not False:
        errors.append("doctor cannot claim general installed-provider inspection")
    if require_containment or "processContainment" in value:
        errors.extend(validate_containment(value.get("processContainment"), expected_target))
    return errors


def smoke(installation: Path, output: Path) -> dict:
    result = {"schema_version": 1, "evidence_kind": "installed_distribution_smoke",
              "state": "failed", "source": source_identity(), "errors": [],
              "paths_with_spaces_and_unicode": True, "actual_codex_host_observation": "not_performed",
              "qualification": "Doctor execution only; exit 0 is not all checks passing or release qualification"}
    name = "ktchecker.bat" if os.name == "nt" else "ktchecker"
    launcher = installation / "bin" / name
    jars = sorted((installation / "lib").glob("*.jar"))
    if not launcher.is_file() or not jars:
        result["errors"].append("Installed launcher or dependency jars are missing")
        return result
    manifest = [{"name": str(path.relative_to(installation)).replace("\\", "/"),
                 "digest": digest_file(path)} for path in [launcher, *jars]]
    result["installation_digest"] = canonical_digest(manifest)
    result["installation_files"] = manifest
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ktchecker CI Δ ", dir=output.parent) as temporary:
        destination = Path(temporary) / "installed preview"
        shutil.copytree(installation, destination)
        copied_launcher = destination / "bin" / name
        if os.name == "nt":
            command = windows_batch_command(copied_launcher, os.environ.get("COMSPEC", "cmd.exe"))
            result["launcher_kind"] = "native_windows_batch"
        else:
            command = [str(copied_launcher), "doctor", "--format", "json"]
            result["launcher_kind"] = "posix_application_script"
        environment = dict(os.environ)
        for key in ("JAVA_OPTS", "KTCHECKER_OPTS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            environment.pop(key, None)
        execution, stdout, _ = run_logged(
            command, cwd=destination, log_base=output.parent / "raw" / "doctor-smoke",
            timeout=90, max_output_bytes=1024 * 1024, env=environment, separate_stderr=True,
        )
        # Temp path is evidence of the launch test, never a command to replay blindly.
        result["execution"] = execution
        if execution["state"] != "completed" or execution["return_code"] != 0:
            result["errors"].append("Installed launcher did not successfully execute doctor")
        try:
            doctor = json.loads(stdout)
        except ValueError:
            result["errors"].append("Installed launcher stdout is not one JSON doctor response")
        else:
            errors = validate_doctor(doctor, require_containment=True,
                                     expected_target=supervisor_target(platform.system(), platform.machine()))
            result["errors"].extend(errors)
            if not errors:
                result["doctor"] = {key: doctor[key] for key in
                                    ("contractVersion", "command", "status", "version", "releaseQualified", "runtime")}
                result["doctor"]["capability_count"] = len(doctor["capabilities"])
                result["doctor"]["gap_count"] = len(doctor["gaps"])
                result["doctor"]["processContainment"] = doctor["processContainment"]
                result["doctor"]["installedProvidersInspected"] = doctor.get("installedProvidersInspected", False)
    result["state"] = "passed" if not result["errors"] else "failed"
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--installation", type=Path, default=REPO_ROOT / "modules/cli/build/install/ktchecker")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = smoke(args.installation.resolve(), args.output.resolve())
    except (OSError, ValueError) as error:
        result = {"schema_version": 1, "evidence_kind": "installed_distribution_smoke", "state": "failed",
                  "errors": [str(error)], "qualification": "Smoke execution failed; no validation implied"}
    write_json(args.output, result)
    print(f"Installed distribution smoke: {result['state']} ({args.output})")
    for error in result["errors"]:
        print(error)
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())

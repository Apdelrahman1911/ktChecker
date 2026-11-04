#!/usr/bin/env python3
"""Execute the installed preview CLI, including paths with spaces and Unicode."""

import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile

if __package__:
    from .ci_common import REPO_ROOT, canonical_digest, digest_file, run_logged, source_identity, write_json
else:
    from ci_common import REPO_ROOT, canonical_digest, digest_file, run_logged, source_identity, write_json


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


def validate_doctor(value: object) -> list[str]:
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
            errors = validate_doctor(doctor)
            result["errors"].extend(errors)
            if not errors:
                result["doctor"] = {key: doctor[key] for key in
                                    ("contractVersion", "command", "status", "version", "releaseQualified", "runtime")}
                result["doctor"]["capability_count"] = len(doctor["capabilities"])
                result["doctor"]["gap_count"] = len(doctor["gaps"])
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

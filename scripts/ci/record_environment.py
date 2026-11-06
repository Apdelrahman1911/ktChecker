#!/usr/bin/env python3
"""Record observed tools/source identity without dumping environment or secrets."""

import argparse
import json
import math
import os
from pathlib import Path
import platform
import re
import selectors
import shutil
import subprocess
import time

if __package__:
    from .ci_common import REPO_ROOT, digest_file, source_identity, utc_now, write_json
    from .provision_android import PACKAGES, installed_packages
else:
    from ci_common import REPO_ROOT, digest_file, source_identity, utc_now, write_json
    from provision_android import PACKAGES, installed_packages


PATH_VARIABLES = ("JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "DEVELOPER_DIR")
RUNNER_VARIABLES = ("RUNNER_OS", "RUNNER_ARCH", "RUNNER_ENVIRONMENT", "ImageOS", "ImageVersion")
GITHUB_VARIABLES = ("GITHUB_ACTIONS", "GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_JOB",
                    "GITHUB_EVENT_NAME", "GITHUB_SHA", "GITHUB_WORKFLOW_SHA", "GITHUB_WORKFLOW_REF")
SIMCTL_JSON_MAX_BYTES = 1024 * 1024
JSON_MAX_DEPTH = 32
JSON_MAX_TOKENS = 32768
PROBE_CLEANUP_SECONDS = 2
SIMCTL_INVENTORIES = {
    "runtimes": ("list", "runtimes", "--json"),
    "devices": ("list", "devices", "available", "--json"),
}


def probe(argv: list[str], timeout: int = 30) -> tuple[int | None, str]:
    """Small human-readable version probes; never use this clipped text for JSON."""
    try:
        result = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True,
                                timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None, ""
    return result.returncode, (result.stdout + result.stderr)[:65536].decode("utf-8", errors="replace")


class StructuredProbeError(ValueError):
    """A fixed public failure code, never a decoder's raw-output excerpt."""


def _parse_probe_json(raw: bytes) -> dict:
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        raise StructuredProbeError("invalid_utf8") from None
    # Bound container depth and token count before the JSON decoder allocates
    # an object tree. Punctuation inside strings does not count as structure.
    depth = tokens = 0
    in_string = escaped = False
    for character in text:
        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
        elif character == '"':
            in_string = True
            tokens += 1
        elif character in "[{":
            depth += 1
            tokens += 1
            if depth > JSON_MAX_DEPTH:
                raise StructuredProbeError("json_depth_limit")
        elif character in "]}":
            depth -= 1
            if depth < 0:
                raise StructuredProbeError("malformed_json")
        elif character in ",:":
            tokens += 1
        if tokens > JSON_MAX_TOKENS:
            raise StructuredProbeError("json_token_limit")

    def object_pairs(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise StructuredProbeError("duplicate_json_keys")
            value[key] = item
        return value

    def reject_constant(_):
        raise StructuredProbeError("nonfinite_json")

    def finite_float(value):
        number = float(value)
        if not math.isfinite(number):
            raise StructuredProbeError("nonfinite_json")
        return number

    try:
        value = json.loads(text, object_pairs_hook=object_pairs,
                           parse_constant=reject_constant, parse_float=finite_float)
    except StructuredProbeError:
        raise
    except (ValueError, RecursionError):
        raise StructuredProbeError("malformed_json") from None
    if not isinstance(value, dict):
        raise StructuredProbeError("json_not_object")
    return value


def probe_json(argv: list[str], timeout: int = 30, *,
               max_stdout_bytes: int = SIMCTL_JSON_MAX_BYTES) -> tuple[dict, dict | None]:
    """Read complete stdout-only JSON with bounded pipes, memory and waiting.

    The returned metadata is public; the parsed object is private and must be
    explicitly projected by callers. No raw output or stderr text is retained.
    This trusted POSIX tool probe has a cooperative deadline plus at most two
    seconds of direct-child cleanup (OS process creation is not interruptible).
    It does not prove descendant cleanup or any Apple/Native execution result.
    """
    if type(timeout) is not int or not 1 <= timeout <= 30:
        raise ValueError("Structured probe timeout must be an integer from 1 to 30 seconds")
    if type(max_stdout_bytes) is not int or not 1 <= max_stdout_bytes <= SIMCTL_JSON_MAX_BYTES:
        raise ValueError("Structured probe stdout bound must be an integer from 1 to 1048576 bytes")
    metadata = {"state": "unavailable", "reason": None, "return_code": None,
                "stdout_bytes_observed": 0, "stderr_bytes_observed": 0,
                "stdout_complete": False, "stderr_complete": False,
                "stdout_limit_bytes": max_stdout_bytes, "timeout_seconds": timeout,
                "cleanup_timeout_seconds": PROBE_CLEANUP_SECONDS,
                "json_depth_limit": JSON_MAX_DEPTH, "json_token_limit": JSON_MAX_TOKENS,
                "json_stream": "stdout", "raw_output_retained": False,
                "cleanup_state": "not_started", "terminated_by_probe": False,
                "elapsed_seconds": 0.0}
    if os.name != "posix":
        metadata["reason"] = "pipe_probe_unavailable"
        return metadata, None
    started = time.monotonic()
    deadline = started + timeout
    try:
        process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, bufsize=0, shell=False)
    except OSError:
        metadata["reason"] = "start_failure"
        metadata["elapsed_seconds"] = round(time.monotonic() - started, 3)
        return metadata, None

    reason = None
    stdout = bytearray()
    try:
        with selectors.DefaultSelector() as selector:
            for name, stream in (("stdout", process.stdout), ("stderr", process.stderr)):
                if stream is None:
                    raise ValueError("Missing structured probe pipe")
                os.set_blocking(stream.fileno(), False)
                selector.register(stream, selectors.EVENT_READ, name)
            while selector.get_map() and reason is None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    reason = "timeout"
                    break
                for key, _ in selector.select(remaining):
                    # One extra stdout byte detects overflow, never a valid
                    # clipped JSON prefix. Stderr is observed but never stored.
                    read_size = min(16384, max_stdout_bytes - len(stdout) + 1) if key.data == "stdout" else 4096
                    try:
                        chunk = os.read(key.fd, read_size)
                    except BlockingIOError:
                        continue
                    metadata[f"{key.data}_bytes_observed"] += len(chunk)
                    if not chunk:
                        metadata[f"{key.data}_complete"] = True
                        selector.unregister(key.fileobj)
                    elif key.data == "stderr":
                        reason = "stderr_output"
                        break
                    elif len(stdout) + len(chunk) > max_stdout_bytes:
                        reason = "stdout_overflow"
                        break
                    else:
                        stdout.extend(chunk)
        if reason is None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                reason = "timeout"
            else:
                metadata["return_code"] = process.wait(timeout=remaining)
                metadata["cleanup_state"] = "direct_child_reaped"
                if metadata["return_code"] != 0:
                    reason = "nonzero_exit"
    except subprocess.TimeoutExpired:
        reason = "timeout"
    except (OSError, ValueError):
        reason = "stream_failure"
    finally:
        for stream in (process.stdout, process.stderr):
            if stream is not None:
                try:
                    stream.close()
                except OSError:
                    reason = reason or "stream_failure"
        if metadata["cleanup_state"] != "direct_child_reaped":
            try:
                # A reaped child ends the signal path. Numeric process groups
                # are deliberately not used by this inventory-only probe.
                if process.poll() is None:
                    metadata["terminated_by_probe"] = True
                    process.kill()
                metadata["return_code"] = process.wait(timeout=PROBE_CLEANUP_SECONDS)
                metadata["cleanup_state"] = "direct_child_reaped"
            except (OSError, subprocess.TimeoutExpired):
                metadata["cleanup_state"] = "direct_child_cleanup_unverified"
                reason = reason or "cleanup_unverified"

    value = None
    if reason is None:
        try:
            value = _parse_probe_json(bytes(stdout))
        except StructuredProbeError as error:
            reason = error.args[0]
        if time.monotonic() >= deadline:
            reason = "timeout"
            value = None
    metadata.update(state="passed" if reason is None else "unavailable", reason=reason,
                    elapsed_seconds=round(time.monotonic() - started, 3))
    return metadata, value if reason is None else None


def validate_simctl_inventory(value: dict, kind: str) -> None:
    """Validate only the typed inventory shape/fields used by our controls."""
    def public_text(item):
        return isinstance(item, str) and 0 < len(item) <= 256 and item.isprintable()

    def row_valid(row, required):
        return isinstance(row, dict) and type(row.get("isAvailable")) is bool and \
            all(public_text(row.get(key)) for key in required) and \
            all(row.get(key) is None or public_text(row[key]) for key in ("name", "buildversion", "state"))

    if not isinstance(value, dict):
        raise ValueError("Invalid simctl inventory schema")
    if kind == "runtimes":
        rows = value.get("runtimes")
        valid = isinstance(rows, list) and all(row_valid(row, ("identifier", "version")) for row in rows)
    elif kind == "devices":
        groups = value.get("devices")
        valid = isinstance(groups, dict) and all(
            public_text(identifier) and isinstance(rows, list) and all(row_valid(row, ("name", "udid")) for row in rows)
            for identifier, rows in groups.items()
        )
    else:
        raise ValueError("Unknown simctl inventory kind")
    if not valid:
        raise ValueError("Invalid simctl inventory schema")


def probe_simctl_inventory(kind: str) -> tuple[dict, dict | None]:
    if kind not in SIMCTL_INVENTORIES:
        raise ValueError("Unknown simctl inventory kind")
    metadata, value = probe_json(["/usr/bin/xcrun", "simctl", *SIMCTL_INVENTORIES[kind]])
    metadata = dict(metadata, inventory_kind=kind)
    if value is not None:
        try:
            validate_simctl_inventory(value, kind)
        except ValueError:
            metadata.update(state="unavailable", reason="invalid_inventory_schema")
            value = None
    return metadata, value


def wrapper_profile(root: Path) -> dict:
    properties_path = root / "gradle/wrapper/gradle-wrapper.properties"
    if not properties_path.is_file():
        return {"state": "absent"}
    properties = {}
    for line in properties_path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip().replace("\\:", ":")
    jar = root / "gradle/wrapper/gradle-wrapper.jar"
    url = properties.get("distributionUrl")
    checksum = properties.get("distributionSha256Sum")
    state = "pinned" if url == "https://services.gradle.org/distributions/gradle-9.6.1-bin.zip" and \
        checksum and re.fullmatch(r"[0-9a-f]{64}", checksum) and jar.is_file() else "incomplete_or_unexpected"
    return {"state": state, "selected_distribution_url": url,
            "selected_distribution_sha256": checksum,
            "wrapper_jar_digest": digest_file(jar) if jar.is_file() else None,
            "gradle_download_performed_by_environment_recorder": False}


def observe_xcode(*, include_simulator_inventory: bool = True) -> dict:
    if platform.system() != "Darwin":
        return {"state": "unavailable", "reason": "Apple execution requires a Darwin host"}
    code, output = probe(["/usr/bin/xcodebuild", "-version"])
    version = re.search(r"^Xcode ([\d.]+)$", output, re.MULTILINE)
    build = re.search(r"^Build version ([\w.]+)$", output, re.MULTILINE)
    observed = code == 0 and version is not None and build is not None
    result = {"state": "observed" if observed else "unavailable",
              "version": version[1] if observed else None, "build": build[1] if observed else None}
    for sdk in ("iphoneos", "iphonesimulator"):
        sdk_code, sdk_output = probe(["/usr/bin/xcrun", "--sdk", sdk, "--show-sdk-version"])
        value = sdk_output.strip()
        result[f"{sdk}_sdk"] = value if sdk_code == 0 and re.fullmatch(r"[\d.]+", value) else None
    if include_simulator_inventory:
        metadata, inventory = probe_simctl_inventory("runtimes")
        result["simulator_runtime_inventory"] = metadata
        runtimes = inventory["runtimes"] if inventory is not None else []
        result["available_simulator_runtimes"] = [
            {key: runtime.get(key) for key in ("identifier", "version", "buildversion", "name")}
            for runtime in runtimes if runtime.get("isAvailable") is True
        ]
    return result


def ci_context_errors(source: dict, host: dict, runner: dict, ci: dict) -> list[str]:
    """Validate public provenance completeness/joins, not hosted-run authentication."""
    errors = []
    token = r"[A-Za-z0-9_.-]{1,128}"
    repository = token + "/" + token
    fields = (
        (runner, "RUNNER_ENVIRONMENT", r"github-hosted"),
        (runner, "RUNNER_OS", r"Linux|Windows|macOS"),
        (runner, "RUNNER_ARCH", r"X86|X64|ARM|ARM64"),
        (runner, "ImageOS", r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"),
        (runner, "ImageVersion", r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"),
        (ci, "GITHUB_ACTIONS", r"true"),
        (ci, "GITHUB_REPOSITORY", repository),
        (ci, "GITHUB_JOB", token),
        (ci, "GITHUB_EVENT_NAME", token),
        (ci, "GITHUB_SHA", r"[0-9a-f]{40}"),
        (ci, "GITHUB_WORKFLOW_SHA", r"[0-9a-f]{40}"),
    )
    for values, key, pattern in fields:
        value = values.get(key)
        if not isinstance(value, str) or re.fullmatch(pattern, value) is None:
            errors.append(f"Missing or invalid required CI context: {key}")
    for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
        value = ci.get(key)
        if not (isinstance(value, str) and re.fullmatch(r"[0-9]{1,20}", value) and int(value) > 0):
            errors.append(f"Missing or invalid required CI context: {key}")
    workflow_ref = ci.get("GITHUB_WORKFLOW_REF")
    if not (isinstance(workflow_ref, str) and 1 <= len(workflow_ref) <= 1024 and workflow_ref.isprintable()
            and re.fullmatch(repository + r"/\.github/workflows/[^/]+@.+", workflow_ref)):
        errors.append("Missing or invalid required CI context: GITHUB_WORKFLOW_REF")
    runner_os = runner.get("RUNNER_OS")
    expected_host = {"Linux": "Linux", "Windows": "Windows", "macOS": "Darwin"}.get(runner_os) \
        if isinstance(runner_os, str) else None
    if expected_host is not None and host.get("os") != expected_host:
        errors.append("Required CI RUNNER_OS does not match observed host OS")
    candidate = ci.get("GITHUB_SHA")
    if isinstance(candidate, str) and re.fullmatch(r"[0-9a-f]{40}", candidate) and candidate != source.get("commit"):
        errors.append("Required CI GITHUB_SHA does not match observed checkout commit")
    # Workflow and candidate commits can legitimately differ; retain both.
    return errors


def record(args: argparse.Namespace) -> dict:
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else shutil.which("java")
    code, text = probe([java, "-version"]) if java else (None, "")
    version = re.search(r'(?:openjdk|java) version "([^"\r\n]+)"', text)
    java_version = version[1] if version and code == 0 else None
    java_major = int(java_version.split(".")[0]) if java_version and java_version.split(".")[0].isdigit() else None
    release_file = Path(java_home) / "release" if java_home else None
    release_properties = {}
    if release_file and release_file.is_file():
        # Java distribution metadata only, never dump arbitrary process output.
        for line in release_file.read_text(encoding="utf-8").splitlines():
            key, separator, value = line.partition("=")
            if separator and key in {"JAVA_VERSION", "JAVA_RUNTIME_VERSION", "IMPLEMENTOR", "IMPLEMENTOR_VERSION", "OS_ARCH"}:
                release_properties[key] = value.strip('"')[:512]
    wrapper = wrapper_profile(REPO_ROOT)
    executed_version = None
    if args.gradle_result and args.gradle_result.is_file():
        value = json.loads(args.gradle_result.read_text(encoding="utf-8"))
        if value.get("state") == "passed":
            executed_version = value.get("executed_gradle_version")
    xcode = observe_xcode()
    errors = []
    if wrapper["state"] != "pinned":
        errors.append("The repository wrapper is not completely pinned to Gradle 9.6.1")
    if java_major != args.require_java_major:
        errors.append(f"Observed Java major {java_major}; required {args.require_java_major}")
    if args.require_gradle and executed_version != args.require_gradle:
        errors.append(f"Executed Gradle version {executed_version}; required {args.require_gradle}")
    if args.require_xcode and (xcode.get("state") != "observed" or xcode.get("version") != args.require_xcode):
        errors.append(f"Observed Xcode {xcode.get('version')}; required {args.require_xcode}")
    if args.require_xcode_build and (xcode.get("state") != "observed" or xcode.get("build") != args.require_xcode_build):
        errors.append(f"Observed Xcode build {xcode.get('build')}; required {args.require_xcode_build}")
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    android_packages = installed_packages(Path(android_home)) if android_home else []
    android = {"sdkmanager_available": shutil.which("sdkmanager") is not None,
               "sdk_root_configured": android_home is not None,
               "compile_sdk_37_installed": any(row["package"] == PACKAGES[0] and row["installed"] for row in android_packages),
               "build_tools_36_0_0_installed": any(row["package"] == PACKAGES[1] and row["installed"] for row in android_packages),
               "packages": android_packages}
    source = source_identity()
    host = {"os": platform.system(), "release": platform.release(), "arch": platform.machine(),
            "python": platform.python_version()}
    runner = {key: os.environ[key] for key in RUNNER_VARIABLES if key in os.environ}
    ci = {key: os.environ[key] for key in GITHUB_VARIABLES if key in os.environ}
    require_ci_context = getattr(args, "require_ci_context", False)
    if require_ci_context:
        errors.extend(ci_context_errors(source, host, runner, ci))
    return {"schema_version": 1, "evidence_kind": "observed_environment", "recorded_at": utc_now(),
            "state": "passed" if not errors else "failed", "errors": errors,
            "source": source, "host": host, "runner": runner, "ci": ci,
            "ci_context_required": require_ci_context,
            "tool_paths": {key: os.environ[key] for key in PATH_VARIABLES if key in os.environ},
            "java": {"path": java, "version": java_version, "major": java_major,
                     "launcher_digest": digest_file(Path(java).resolve()) if java and Path(java).is_file() else None,
                     "release_properties": release_properties,
                     "release_file_digest": digest_file(release_file) if release_file and release_file.is_file() else None,
                     "whole_distribution_hash_locked": False},
            "gradle": {**wrapper, "executed_wrapper_version": executed_version,
                       "system_gradle_path": shutil.which("gradle"),
                       "system_gradle_is_not_the_selected_wrapper": True},
            "android": android, "xcode": xcode,
            "actual_codex_host_observation": {"state": "not_performed", "reason": "Not assessed by this CI environment probe"},
            "qualification": "Tool availability only; no target execution or rule qualification implied"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--gradle-result", type=Path)
    parser.add_argument("--require-java-major", type=int, default=21)
    parser.add_argument("--require-gradle")
    parser.add_argument("--require-xcode")
    parser.add_argument("--require-xcode-build")
    parser.add_argument("--require-ci-context", action="store_true",
                        help="Require complete hosted runner/image and GitHub source/workflow provenance")
    args = parser.parse_args()
    try:
        result = record(args)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        result = {"schema_version": 1, "evidence_kind": "observed_environment", "state": "failed",
                  "recorded_at": utc_now(), "errors": [type(error).__name__],
                  "qualification": "Environment recording failed; no validation implied"}
    write_json(args.output, result)
    print(f"Environment evidence: {result['state']} ({args.output})")
    for error in result["errors"]:
        print(error)
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())

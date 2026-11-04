#!/usr/bin/env python3
"""Record observed tools/source identity without dumping environment or secrets."""

import argparse
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess

if __package__:
    from .ci_common import REPO_ROOT, digest_file, source_identity, utc_now, write_json
    from .provision_android import PACKAGES, installed_packages
else:
    from ci_common import REPO_ROOT, digest_file, source_identity, utc_now, write_json
    from provision_android import PACKAGES, installed_packages


PATH_VARIABLES = ("JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "DEVELOPER_DIR")
RUNNER_VARIABLES = ("RUNNER_OS", "RUNNER_ARCH", "RUNNER_ENVIRONMENT", "ImageOS", "ImageVersion")
GITHUB_VARIABLES = ("GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_JOB", "GITHUB_EVENT_NAME")


def probe(argv: list[str], timeout: int = 30) -> tuple[int | None, str]:
    try:
        result = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True,
                                timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None, ""
    return result.returncode, (result.stdout + result.stderr)[:65536].decode("utf-8", errors="replace")


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


def observe_xcode() -> dict:
    if platform.system() != "Darwin":
        return {"state": "unavailable", "reason": "Apple execution requires a Darwin host"}
    code, output = probe(["/usr/bin/xcodebuild", "-version"])
    version = re.search(r"^Xcode ([\d.]+)$", output, re.MULTILINE)
    build = re.search(r"^Build version ([\w.]+)$", output, re.MULTILINE)
    result = {"state": "observed" if code == 0 and version and build else "unavailable",
              "version": version[1] if version else None, "build": build[1] if build else None}
    for sdk in ("iphoneos", "iphonesimulator"):
        sdk_code, sdk_output = probe(["/usr/bin/xcrun", "--sdk", sdk, "--show-sdk-version"])
        value = sdk_output.strip()
        result[f"{sdk}_sdk"] = value if sdk_code == 0 and re.fullmatch(r"[\d.]+", value) else None
    code, text = probe(["/usr/bin/xcrun", "simctl", "list", "runtimes", "--json"])
    try:
        runtimes = json.loads(text).get("runtimes", []) if code == 0 else []
        result["available_simulator_runtimes"] = [
            {key: runtime.get(key) for key in ("identifier", "version", "buildversion", "name")}
            for runtime in runtimes if runtime.get("isAvailable") is True
        ]
    except (ValueError, AttributeError, TypeError):
        result["available_simulator_runtimes"] = []
    return result


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
    if args.require_xcode and xcode.get("version") != args.require_xcode:
        errors.append(f"Observed Xcode {xcode.get('version')}; required {args.require_xcode}")
    if args.require_xcode_build and xcode.get("build") != args.require_xcode_build:
        errors.append(f"Observed Xcode build {xcode.get('build')}; required {args.require_xcode_build}")
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    android_packages = installed_packages(Path(android_home)) if android_home else []
    android = {"sdkmanager_available": shutil.which("sdkmanager") is not None,
               "sdk_root_configured": android_home is not None,
               "compile_sdk_37_installed": any(row["package"] == PACKAGES[0] and row["installed"] for row in android_packages),
               "build_tools_36_0_0_installed": any(row["package"] == PACKAGES[1] and row["installed"] for row in android_packages),
               "packages": android_packages}
    return {"schema_version": 1, "evidence_kind": "observed_environment", "recorded_at": utc_now(),
            "state": "passed" if not errors else "failed", "errors": errors,
            "source": source_identity(),
            "host": {"os": platform.system(), "release": platform.release(), "arch": platform.machine(),
                     "python": platform.python_version()},
            "runner": {key: os.environ[key] for key in RUNNER_VARIABLES if key in os.environ},
            "ci": {key: os.environ[key] for key in GITHUB_VARIABLES if key in os.environ},
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

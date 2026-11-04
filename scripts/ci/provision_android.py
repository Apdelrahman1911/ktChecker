#!/usr/bin/env python3
"""Install the two explicit fixture SDK packages using the runner's SDK manager.

Uses the hosted image's previously accepted licenses. An absent SDK manager,
unaccepted license or failed installation fails closed, rather than substituting
another API level or silently accepting new license terms.
"""

import argparse
import os
from pathlib import Path
import re
import shutil

if __package__:
    from .ci_common import digest_file, run_logged, utc_now, write_json
else:
    from ci_common import digest_file, run_logged, utc_now, write_json


PACKAGES = ("platforms;android-37.0", "build-tools;36.0.0")
PLATFORM_PATH = "platforms/android-37.0"
PROPERTY_KEYS = {"Pkg.Revision", "Pkg.Desc", "Pkg.Path", "AndroidVersion.ApiLevel", "AndroidVersion.CodeName",
                 "AndroidVersion.ExtensionLevel", "AndroidVersion.IsBaseSdk", "AndroidVersion.PreviewSdkInt"}
MAX_PROPERTIES_BYTES = 256 * 1024


def properties_file(path: Path) -> dict:
    properties = {}
    if path.is_file():
        if path.stat().st_size > MAX_PROPERTIES_BYTES:
            raise ValueError("SDK package properties exceed their inventory bound")
        for line in path.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                if key.strip() in PROPERTY_KEYS:
                    if key.strip() in properties:
                        raise ValueError("SDK package properties contain an ambiguous selected metadata key")
                    properties[key.strip()] = re.sub(r"[\x00-\x1f\x7f]", "?", value.strip())[:512]
    return properties


def failure_categories(output: str) -> list[str]:
    """Known tool failure categories, never raw messages/paths/license prompts."""
    patterns = {"selected_package_unavailable": r"(?:failed to find|could not find|cannot find|not found)[^\n]{0,100}(?:platforms;android-37\.0|build-tools;36\.0\.0)",
                "sdk_license_acceptance_unavailable": r"(?:licenses?[^\n]{0,100}(?:not accepted|not been accepted)|unaccepted licenses?|do you accept the license)",
                "sdk_repository_download_failed": r"(?:failed to download|IO exception[^\n]{0,100}(?:manifest|source list))",
                "sdk_metadata_parse_failed": r"(?:NumberFormatException|failed to parse[^\n]{0,100}(?:XML|manifest)|could not parse[^\n]{0,100}(?:XML|manifest))",
                "sdk_manager_java_incompatible": r"(?:UnsupportedClassVersionError|NoClassDefFoundError|ClassNotFoundException)",
                "sdk_manager_argument_rejected": r"(?:unknown argument|unrecognized option)"}
    return sorted(name for name, pattern in patterns.items() if re.search(pattern, output, re.IGNORECASE))


def installed_packages(sdk: Path) -> list[dict]:
    result = []
    for package, relative in zip(PACKAGES, (PLATFORM_PATH, "build-tools/36.0.0")):
        properties_path = sdk / relative / "source.properties"
        properties = properties_file(properties_path)
        platform = package.startswith("platforms;")
        primary = sdk / relative / "android.jar" if platform else properties_path
        api = properties.get("AndroidVersion.ApiLevel")
        observed_api = {"raw": api, "major": int(api.split(".")[0]), "minor": int(api.split(".")[1]) if "." in api else 0} \
            if api and re.fullmatch(r"[1-9]\d*(?:\.\d+)?", api) else None
        if platform:
            matching = api in {"37", "37.0"} and not properties.get("AndroidVersion.CodeName") \
                and properties.get("AndroidVersion.IsBaseSdk", "true") == "true" \
                and properties.get("AndroidVersion.PreviewSdkInt", "0") == "0"
        else:
            matching = properties.get("Pkg.Revision") == "36.0.0"
        matching = matching and properties.get("Pkg.Path", package) == package
        result.append({"package": package, "installed": bool(primary.is_file() and matching), "properties": properties,
                       "observed_android_api": observed_api, "expected_android_api": {"major": 37, "minor": 0} if platform else None,
                       "source_properties_digest": digest_file(properties_path) if properties_path.is_file() else None,
                       "primary_artifact_digest": digest_file(primary) if primary.is_file() else None,
                       "whole_distribution_hash_locked": False})
    return result


def provision(output: Path) -> dict:
    sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    result = {"schema_version": 1, "evidence_kind": "sdk_provisioning", "recorded_at": utc_now(),
              "state": "unavailable", "errors": [], "selected_packages": list(PACKAGES),
              "qualification": "SDK availability, not Android target execution"}
    if not sdk_value:
        result["errors"].append("Hosted Android SDK root is not configured")
        return result
    sdk = Path(sdk_value)
    result["sdk_root"] = str(sdk)
    before = installed_packages(sdk)
    result["packages"] = before
    if not all(package["installed"] for package in before):
        manager = shutil.which("sdkmanager")
        if not manager:
            candidate = sdk / "cmdline-tools/latest/bin/sdkmanager"
            manager = str(candidate) if candidate.is_file() else None
        if not manager or os.name == "nt" and manager.endswith(".bat"):
            result["errors"].append("A direct SDK manager is unavailable for this Linux/macOS provisioning lane")
            return result
        result["sdkmanager_properties"] = properties_file(Path(manager).resolve().parent.parent / "source.properties")
        execution, stdout, stderr = run_logged(
            [manager, f"--sdk_root={sdk}", *PACKAGES], cwd=output.parent.resolve(),
            log_base=output.parent / "raw" / "sdk-provisioning", timeout=300,
        )
        result["execution"] = execution
        result["failure_categories"] = failure_categories(stdout + "\n" + stderr)
        if execution["state"] != "completed" or execution["return_code"] != 0:
            result["errors"].append("SDK provisioning failed; check package availability and runner license acceptance")
    result["packages"] = installed_packages(sdk)
    if not all(package["installed"] for package in result["packages"]):
        result["errors"].append("Required compileSdk 37 / Build Tools 36.0.0 are missing")
    result["state"] = "passed" if not result["errors"] else "failed"
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        result = provision(args.output)
    except (OSError, ValueError) as error:
        result = {"schema_version": 1, "evidence_kind": "sdk_provisioning", "state": "failed",
                  "selected_packages": list(PACKAGES), "errors": [f"SDK provisioning inventory failed: {type(error).__name__}"],
                  "qualification": "No successful SDK inventory or target execution claimed"}
    write_json(args.output, result)
    print(f"SDK provisioning: {result['state']} ({args.output})")
    for error in result["errors"]:
        print(error)
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())

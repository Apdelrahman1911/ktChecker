#!/usr/bin/env python3
"""Install the two explicit fixture SDK packages using the runner's SDK manager.

Uses the hosted image's previously accepted licenses. An absent SDK manager,
unaccepted license or failed installation fails closed, rather than substituting
another API level or silently accepting new license terms.
"""

import argparse
import os
from pathlib import Path
import shutil

if __package__:
    from .ci_common import run_logged, utc_now, write_json
else:
    from ci_common import run_logged, utc_now, write_json


PACKAGES = ("platforms;android-37", "build-tools;36.0.0")


def installed_packages(sdk: Path) -> list[dict]:
    result = []
    for package, relative in zip(PACKAGES, ("platforms/android-37", "build-tools/36.0.0")):
        properties_path = sdk / relative / "source.properties"
        properties = {}
        if properties_path.is_file():
            for line in properties_path.read_text(encoding="utf-8").splitlines():
                if "=" in line and not line.lstrip().startswith("#"):
                    key, value = line.split("=", 1)
                    if key.strip() in {"Pkg.Revision", "AndroidVersion.ApiLevel", "Pkg.Desc"}:
                        properties[key.strip()] = value.strip()
        present = (sdk / relative / "android.jar").is_file() if package.startswith("platforms;") else properties_path.is_file()
        result.append({"package": package, "installed": present, "properties": properties})
    return result


def provision(output: Path) -> dict:
    sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    result = {"schema_version": 1, "evidence_kind": "sdk_provisioning", "recorded_at": utc_now(),
              "state": "unavailable", "errors": [], "qualification": "SDK availability, not Android target execution"}
    if not sdk_value:
        result["errors"].append("Hosted Android SDK root is not configured")
        return result
    sdk = Path(sdk_value)
    result["sdk_root"] = str(sdk)
    before = installed_packages(sdk)
    if not all(package["installed"] for package in before):
        manager = shutil.which("sdkmanager")
        if not manager:
            candidate = sdk / "cmdline-tools/latest/bin/sdkmanager"
            manager = str(candidate) if candidate.is_file() else None
        if not manager or os.name == "nt" and manager.endswith(".bat"):
            result["errors"].append("A direct SDK manager is unavailable for this Linux/macOS provisioning lane")
            return result
        execution, _, _ = run_logged(
            [manager, f"--sdk_root={sdk}", *PACKAGES], cwd=output.parent.resolve(),
            log_base=output.parent / "raw" / "sdk-provisioning", timeout=300,
        )
        result["execution"] = execution
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
        result = {"schema_version": 1, "evidence_kind": "sdk_provisioning", "state": "failed", "errors": [str(error)]}
    write_json(args.output, result)
    print(f"SDK provisioning: {result['state']} ({args.output})")
    for error in result["errors"]:
        print(error)
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())

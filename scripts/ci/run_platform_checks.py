#!/usr/bin/env python3
"""Execute real small target controls, never passing Linux as iOS execution."""

import argparse
import json
import os
from pathlib import Path
import platform
import re
import xml.etree.ElementTree as ET

if __package__:
    from . import provision_native as native
    from .ci_common import REPO_ROOT, digest_bytes, gradle_result, source_identity, utc_now, write_json
    from .collect_evidence import artifact, junit_report
    from .provision_android import installed_packages
    from .record_environment import observe_xcode, probe, probe_simctl_inventory, validate_simctl_inventory
else:
    import provision_native as native
    from ci_common import REPO_ROOT, digest_bytes, gradle_result, source_identity, utc_now, write_json
    from collect_evidence import artifact, junit_report
    from provision_android import installed_packages
    from record_environment import observe_xcode, probe, probe_simctl_inventory, validate_simctl_inventory


IOS_TASKS = ("compileKotlinIosArm64", "compileKotlinIosSimulatorArm64", "linkDebugFrameworkIosArm64",
             "linkDebugFrameworkIosSimulatorArm64", "iosSimulatorArm64Test")
NATIVE_DIAGNOSTICS = {
    "missing-actual": r"^\s*e:[^\n]*\[NO_ACTUAL_FOR_EXPECT\][\s\S]{0,1500}\bplatformFamily\b",
    "wrong-actual": r"^\s*e:[^\n]*\[(?:ACTUAL_WITHOUT_EXPECT|EXPECT_ACTUAL_INCOMPATIBLE_RETURN_TYPE)\][\s\S]{0,1500}\bplatformFamily\b",
}


def select_simulator(runtimes: dict, devices: dict) -> dict:
    validate_simctl_inventory(runtimes, "runtimes")
    validate_simctl_inventory(devices, "devices")
    for runtime in runtimes.get("runtimes", []):
        if runtime.get("isAvailable") is not True or runtime.get("version") not in {"26.4", "26.4.0"}:
            continue
        identifier = runtime.get("identifier", "")
        if not re.fullmatch(r"com\.apple\.CoreSimulator\.SimRuntime\.iOS-26-4(?:-0)?", identifier):
            continue
        for device in devices.get("devices", {}).get(identifier, []):
            if device.get("isAvailable") is not True or device.get("name") != "iPhone 17":
                continue
            udid = device.get("udid", "")
            if not re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", udid):
                continue
            return {"name": device["name"], "udid": udid, "runtime_identifier": identifier,
                    "runtime_version": runtime["version"], "runtime_build": runtime.get("buildversion"),
                    "initial_state": device.get("state"), "execution_mode": "KGP simctl spawn --standalone"}
    raise ValueError("No available iPhone 17 in the pinned iOS 26.4 simulator runtime")


def preflight(profile: str) -> dict:
    errors = []
    java_home = os.environ.get("JAVA_HOME")
    java_path = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else "java"
    # The wrapper selects JAVA_HOME before PATH; observe that same project JVM.
    code, text = probe([java_path, "-version"])
    version = re.search(r'(?:openjdk|java) version "([^"\r\n]+)"', text)
    java_version = version[1] if code == 0 and version else None
    if not java_version or java_version.split(".")[0] != "25":
        errors.append("Fixture project/compiler toolchain requires an observed Java 25 runtime; broker JVM 21 is separate")
    sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    packages = installed_packages(Path(sdk_value)) if sdk_value else []
    if not packages or not all(package["installed"] for package in packages):
        errors.append("Android plugin configuration requires compileSdk 37 and Build Tools 36.0.0")
    result = {"java_version": java_version, "project_java_home": java_home, "java_path": java_path,
              "engine_broker_java_major": 21, "required_project_java_major": 25,
              "android_packages": packages, "errors": errors}
    if profile == "ios":
        if platform.system() != "Darwin":
            errors.append("iOS compile/link/simulator execution is unavailable on a non-Darwin host")
        if platform.machine().lower() not in {"arm64", "aarch64"}:
            errors.append("The iOS simulator arm64 fixture requires an arm64 Mac")
        # Runtime/device inventory is probed below only after the fixed host,
        # JVM, Android configuration and selected-Xcode controls are available.
        xcode = observe_xcode(include_simulator_inventory=False)
        result["xcode"] = xcode
        if xcode.get("state") != "observed" or xcode.get("version") != "26.4.1" or xcode.get("build") != "17E202":
            errors.append("Select Xcode 26.4.1, build 17E202 explicitly; runner defaults are not evidence")
        if any(xcode.get(f"{sdk}_sdk") != "26.4" for sdk in ("iphoneos", "iphonesimulator")):
            errors.append("The selected Xcode must expose both iOS 26.4 SDKs")
        if not errors:
            runtime_metadata, runtimes = probe_simctl_inventory("runtimes")
            result["simulator_inventory"] = {"runtimes": runtime_metadata}
            if runtimes is None:
                errors.append(f"simctl runtimes prerequisite unavailable: {runtime_metadata['reason']}")
            else:
                device_metadata, devices = probe_simctl_inventory("devices")
                result["simulator_inventory"]["devices"] = device_metadata
                if devices is None:
                    errors.append(f"simctl devices prerequisite unavailable: {device_metadata['reason']}")
                else:
                    try:
                        result["simulator"] = select_simulator(runtimes, devices)
                    except ValueError as error:
                        # select_simulator emits fixed schema/selection errors,
                        # never JSON decoder excerpts or raw simctl stderr.
                        errors.append(str(error))
    result["state"] = "passed" if not errors else "unavailable"
    return result


def test_context(build: Path, marker: str) -> dict:
    reports = []
    for path in sorted((build / "test-results").rglob("*.xml")):
        if marker.lower() in path.relative_to(build / "test-results").as_posix().lower():
            reports.append(junit_report(path))
    passed = sum(report["counts"]["passed"] for report in reports)
    bad = sum(report["counts"]["failure"] + report["counts"]["error"] + report["counts"]["skipped"] for report in reports)
    errors = [error for report in reports for error in report["inconsistencies"]]
    if not passed:
        errors.append(f"No actual passed test cases for required context {marker}")
    if bad:
        errors.append(f"Required target context {marker} contains failed, errored or skipped test cases")
    return {"state": "passed" if not errors else "failed", "reports": reports, "errors": errors}


def lint_report(path: Path) -> dict:
    raw = path.read_bytes()
    if len(raw) > 8 * 1024 * 1024 or re.search(br"<!\s*(?:DOCTYPE|ENTITY)\b", raw, re.IGNORECASE):
        raise ValueError("Unsafe or oversized Lint report")
    document = ET.fromstring(raw)
    if document.tag != "issues":
        raise ValueError("Not a Lint issues report")
    issues = []
    for issue in document.findall("issue"):
        locations = []
        for location in issue.findall("location"):
            line = location.get("line", "")
            locations.append({"file": location.get("file", "").replace("\\", "/"),
                              "line": int(line) if line.isdigit() else None})
        issues.append({"id": issue.get("id"), "severity": issue.get("severity"), "locations": locations})
    return {"digest": digest_bytes(raw), "issues": issues, "checked_scope": ["NewApi"],
            "messages_and_source_omitted": True}


def execute(args: argparse.Namespace) -> dict:
    fixture = args.fixture.resolve()
    if not fixture.is_relative_to(REPO_ROOT) or not (fixture / "settings.gradle.kts").is_file():
        raise ValueError("Fixture must be a standalone captured project inside this checkout")
    fixture_source = source_identity(fixture)
    prerequisites = preflight(args.profile)
    result = {"schema_version": 1, "evidence_kind": "executed_target_controls", "profile": args.profile,
              "started_at": utc_now(), "state": "running", "fixture_source": fixture_source,
              "source": source_identity(), "prerequisites": prerequisites, "stages": [], "errors": [],
              "qualification": "Small explicit target/diagnostic controls only; not all rule contexts or field precision",
              "provider_adapter_integration": "separate provider harness job step; not implied by direct Gradle tasks",
              "android_device_execution": "not performed", "actual_codex_host_observation": "not performed"}
    home = None
    if args.profile == "ios" and prerequisites["state"] == "passed":
        try:
            _, java_home, selected_xcode = native.observed_prerequisites(args.environment, REPO_ROOT, result["source"])
            home, _, reference = native.consume_selection(args.native_selection, args.native_provisioning, "iosSimulatorArm64",
                                                         REPO_ROOT, result["source"], java_home, selected_xcode)
            # The same explicit enrollment must include the real device target.
            if "iosArm64" not in reference["selected_targets"]:
                native.fail("native_selection_target_mismatch")
            prerequisites["native_selection"] = reference
        except native.NativePrerequisiteError as error:
            prerequisites["errors"].append(error.code)
            prerequisites["state"] = "unavailable"
        except (OSError, ValueError, TypeError, KeyError, AttributeError):
            prerequisites["errors"].append("native_platform_selection_unclassified_failure")
            prerequisites["state"] = "unavailable"
    if prerequisites["state"] != "passed":
        result.update(state="unavailable", errors=prerequisites["errors"])
        write_json(args.output, result)
        return result
    build = fixture / "build"
    shared = ["-p", str(fixture), "--rerun-tasks", "--no-build-cache"]
    if args.profile == "ios":
        shared.append(f"-Pkotlin.native.home={home}")
        shared.append(f"-PfixtureSimulatorDevice={prerequisites['simulator']['udid']}")

    def stage(name: str, tasks: list[str], *, negative: str | None = None,
              failed_task: str | tuple[str, ...] | None = None, diagnostic: str | None = None,
              require: tuple[str, ...] = ()) -> tuple[dict, str]:
        arguments = shared + ([f"-PfixtureNegativeCase={negative}"] if negative else []) + tasks
        value, output = gradle_result(arguments, log_base=args.output.parent / "raw" / name,
                                      timeout=args.timeout, require_tasks=require,
                                      expected_failure=negative is not None,
                                      failed_task=failed_task, diagnostic_pattern=diagnostic)
        value.update(name=name, variant=negative or "none", fixture_input=fixture_source)
        result["stages"].append(value)
        write_json(args.output, result)
        print(f"Target stage {name}: {value['state']}", flush=True)
        if value["state"] != "passed":
            print(output[-8000:])
        return value, output

    if args.profile == "desktop":
        positive, _ = stage("desktop-positive", ["desktopTest"], require=(":desktopTest",))
        if positive["state"] == "passed":
            positive["test_execution"] = test_context(build, "desktopTest")
            positive["errors"].extend(positive["test_execution"]["errors"])
    elif args.profile == "android":
        report_path = build / "reports/lint-results.xml"
        report_path.unlink(missing_ok=True)
        positive, _ = stage("android-positive", ["fixtureAndroidHostTest", "lint"], require=(":fixtureAndroidHostTest",))
        if positive["state"] == "passed":
            executed_tests = [task for task, state in positive["tasks"].items()
                              if "android" in task.lower() and "test" in task.lower() and state == "EXECUTED"
                              and "compile" not in task.lower() and "fixture" not in task.lower()]
            if not executed_tests:
                positive["errors"].append("The concrete Android host-side Test task did not execute")
            positive["test_execution"] = test_context(build, "android")
            positive["errors"].extend(positive["test_execution"]["errors"])
            positive["lint"] = lint_report(report_path)
            if any(issue["id"] == "NewApi" or issue["severity"] in {"Error", "Fatal"} for issue in positive["lint"]["issues"]):
                positive["errors"].append("Clean NewApi control reported an error")
        if not positive["errors"]:
            report_path.unlink(missing_ok=True)
            negative, _ = stage("android-new-api-negative", ["lint"], negative="new-api",
                                failed_task=(":lintAndroidMain", ":lintReportAndroidMain", ":lint"), diagnostic=r"\bNewApi\b")
            if negative["state"] == "passed":
                negative["lint"] = lint_report(report_path)
                findings = [issue for issue in negative["lint"]["issues"] if issue["id"] == "NewApi"
                            and issue["severity"] in {"Error", "Fatal"}
                            and any(location["file"].endswith("/Notification.kt") and location["line"]
                                    for location in issue["locations"])]
                if not findings:
                    negative["errors"].append("NewApi did not identify the controlled invalid Notification.kt")
    else:
        positive, _ = stage("ios-positive", list(IOS_TASKS), require=tuple(":" + task for task in IOS_TASKS))
        if positive["state"] == "passed":
            positive["test_execution"] = test_context(build, "iosSimulatorArm64Test")
            positive["errors"].extend(positive["test_execution"]["errors"])
            positive["artifacts"] = []
            for target in ("iosArm64", "iosSimulatorArm64"):
                framework = build / "bin" / target / "debugFramework/KtCheckerFixture.framework"
                if not (framework / "KtCheckerFixture").is_file():
                    positive["errors"].append(f"Linked {target} framework binary is absent")
                else:
                    positive["artifacts"].append(artifact(framework))
        if not positive["errors"]:
            for case, pattern in NATIVE_DIAGNOSTICS.items():
                for target in ("IosArm64", "IosSimulatorArm64"):
                    task = "compileKotlin" + target
                    stage(f"ios-{case}-{target}", [task], negative=case,
                          failed_task=":" + task, diagnostic=pattern)

    for value in result["stages"]:
        if value["errors"]:
            value["state"] = "failed"
            result["errors"].extend(f"{value['name']}: {error}" for error in value["errors"])
    after = source_identity(fixture)
    result["fixture_source_after_execution"] = after
    if after["working_tree_digest"] != fixture_source["working_tree_digest"]:
        result["errors"].append("Fixture inputs changed during execution; results cannot bind one immutable identity")
    if home is not None:
        try:
            native.consume_selection(args.native_selection, args.native_provisioning, "iosSimulatorArm64",
                                     REPO_ROOT, result["source"], java_home, selected_xcode)
        except native.NativePrerequisiteError as error:
            result["errors"].append(error.code)
        except (OSError, ValueError, TypeError, KeyError, AttributeError):
            result["errors"].append("native_platform_selection_unclassified_failure")
    result["finished_at"] = utc_now()
    result["state"] = "passed" if not result["errors"] and result["stages"] else "failed"
    write_json(args.output, result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("desktop", "android", "ios"), required=True)
    parser.add_argument("--fixture", type=Path, default=REPO_ROOT / "fixtures/kmp")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--environment", type=Path, default=REPO_ROOT / "build/ci-evidence/environment.json")
    parser.add_argument("--native-selection", type=Path, default=REPO_ROOT / "build/ci-evidence/raw/native-provisioning/selection.json")
    parser.add_argument("--native-provisioning", type=Path, default=REPO_ROOT / "build/ci-evidence/native.json")
    parser.add_argument("--timeout", type=int, default=1200)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 2400:
        parser.error("timeout must be between 1 and 2400 seconds per bounded command")
    try:
        result = execute(args)
    except (OSError, ValueError, ET.ParseError) as error:
        try:
            result = json.loads(args.output.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            result = {"schema_version": 1, "evidence_kind": "executed_target_controls",
                      "profile": args.profile, "errors": [],
                      "qualification": "Harness failed; no successful target execution implied"}
        result["state"] = "failed"
        result["errors"].append(f"{type(error).__name__}: {error}")
        write_json(args.output, result)
    print(f"Target controls: {result['state']} ({args.output})")
    for error in result["errors"]:
        print(error)
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Run actual platform adapters against owner-trusted immutable fixture captures."""

import argparse
import json
import os
from pathlib import Path
import re

if __package__:
    from .ci_common import REPO_ROOT, digest_file, gradle_result, read_bounded, source_identity, utc_now, write_json
    from .run_platform_checks import preflight
else:
    from ci_common import REPO_ROOT, digest_file, gradle_result, read_bounded, source_identity, utc_now, write_json
    from run_platform_checks import preflight


EXPECTED_NATIVE = {
    "missing-actual": {"NO_ACTUAL_FOR_EXPECT"},
    "wrong-actual": {"ACTUAL_WITHOUT_EXPECT", "EXPECT_ACTUAL_INCOMPATIBLE_RETURN_TYPE"},
}
CANONICAL_IDS = {
    "NO_ACTUAL_FOR_EXPECT": "ktc.compiler.ir.no_actual_for_expect",
    "ACTUAL_WITHOUT_EXPECT": "ktc.compiler.common.actual_without_expect",
    "EXPECT_ACTUAL_INCOMPATIBLE_RETURN_TYPE": "ktc.compiler.common.expect_actual_incompatible_return_type",
    "NewApi": "ktc.android.lint.NewApi",
}


def valid_digest(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", value) is not None


def relative_source_path(value: object) -> bool:
    return isinstance(value, str) and bool(value) and "\\" not in value and not re.match(r"^[A-Za-z]:", value) \
        and not Path(value).is_absolute() and ".." not in Path(value).parts


def validate_driver(value: object, profile: str, case: str, target: str) -> list[str]:
    if not isinstance(value, dict):
        return ["Provider driver did not emit a JSON object"]
    errors = []
    if type(value.get("schemaVersion")) is not int or value["schemaVersion"] != 1 or value.get("state") != "passed":
        errors.append("Provider driver's actual fixture assertions did not pass")
    if value.get("case") != case or value.get("target") != target:
        errors.append("Provider driver response is for the wrong fixture case/target")
    if not valid_digest(value.get("snapshotId")):
        errors.append("Provider driver omitted immutable fixture snapshot identity")
    artifacts = value.get("artifactIdentities")
    if not isinstance(artifacts, list) or not artifacts or any(
        not isinstance(artifact, dict) or not isinstance(artifact.get("coordinate"), str) or not artifact["coordinate"].strip()
        or not valid_digest(artifact.get("digest")) or not isinstance(artifact.get("path"), str) or not artifact["path"]
        for artifact in artifacts
    ):
        errors.append("Provider driver omitted exact tool/artifact identities")
    provider = value.get("providerResult")
    if not isinstance(provider, dict):
        return errors + ["Provider driver omitted its actual ProviderResult"]
    expected_id = "kotlin-native" if profile == "ios" else "android-lint"
    if provider.get("providerId") != expected_id:
        errors.append("Wrong provider identity in fixture result")
    if provider.get("providerVersion") != ("2.4.21" if profile == "ios" else "32.3.1"):
        errors.append("Actual provider version differs from the selected platform baseline")
    # Incomplete qualification is honest even when the small fixture assertions
    # pass. Unavailable/crashed execution is not a passing adapter integration.
    if provider.get("status") != "incomplete":
        errors.append("Provider fixture must retain honest incomplete qualification, not unavailable/failed/complete status")
    if provider.get("rawDiagnostics") != []:
        errors.append("Provider left unresolved raw diagnostics instead of normalized findings")
    coverage = provider.get("coverage")
    if not isinstance(coverage, list) or not coverage:
        errors.append("Provider emitted no explicit coverage/gaps")
    elif any(not isinstance(row, dict) or row.get("qualified") is not False for row in coverage):
        errors.append("A fixture alone must not mark any rule qualified")
    if isinstance(coverage, list):
        if profile == "android" and {row.get("ruleId") for row in coverage if isinstance(row, dict) and isinstance(row.get("ruleId"), str)} != {"ktc.android.lint.NewApi"}:
            errors.append("Android control coverage is not exactly the selected NewApi scope")
        for row in coverage:
            if not isinstance(row, dict):
                continue
            context = row.get("context")
            if not isinstance(context, dict) or context.get("target") != target:
                errors.append("Coverage does not bind the actual selected target context")
            if row.get("provenance") != "executed" or not valid_digest(row.get("certificateId")):
                errors.append("Coverage omitted actual executed provenance/output certificate")
            if row.get("state") != "incomplete":
                errors.append("Fixture execution must not promote coverage to a qualified/successful state")
    findings = provider.get("findings")
    if not isinstance(findings, list):
        return errors + ["Provider omitted findings array"]
    if any(not isinstance(finding, dict) for finding in findings):
        errors.append("Provider emitted malformed finding objects")
    if case == "positive":
        if any(isinstance(finding, dict) and finding.get("severity") == "error" for finding in findings):
            errors.append("Positive fixture unexpectedly emitted an error finding")
    else:
        expected = EXPECTED_NATIVE.get(case, set()) if profile == "ios" else {"NewApi"}
        controlled = [finding for finding in findings if isinstance(finding, dict)
                      and finding.get("upstreamId") in expected and finding.get("severity") == "error"]
        if not controlled:
            errors.append("Adapter did not normalize the expected actual diagnostic")
        for finding in controlled:
            if finding.get("ruleId") != CANONICAL_IDS[finding["upstreamId"]] or finding.get("providerId") != expected_id:
                errors.append("Expected diagnostic is not normalized to its exact canonical rule/provider ID")
            if not isinstance(coverage, list) or not any(isinstance(row, dict) and row.get("ruleId") == finding.get("ruleId") for row in coverage):
                errors.append("Expected diagnostic has no matching explicit coverage row")
            context = finding.get("context")
            if not isinstance(context, dict) or context.get("target") != target:
                errors.append("Expected diagnostic is for the wrong target context")
            location = finding.get("location")
            if not isinstance(location, dict) or not valid_digest(location.get("fileDigest")) or not relative_source_path(location.get("path")):
                errors.append("Expected diagnostic omitted captured-source identity")
            elif type(location.get("line")) is not int or location["line"] < 1:
                errors.append("Expected diagnostic omitted an actual captured-source line")
            elif profile == "android" and not location["path"].endswith("/Notification.kt"):
                errors.append("NewApi did not point to the controlled invalid Notification.kt")
    return errors


def sanitized_driver(value: dict) -> dict:
    provider = value.get("providerResult", {})
    provider = provider if isinstance(provider, dict) else {}

    def fields(row: object, keys: tuple[str, ...]) -> dict:
        return {key: row.get(key) for key in keys} if isinstance(row, dict) else {}

    def context(row: object) -> dict:
        return fields(row, ("target", "sourceSet", "module", "compilation"))

    result = fields(value, ("schemaVersion", "state", "case", "target", "snapshotId"))
    result["artifactIdentities"] = [fields(row, ("coordinate", "digest", "path"))
                                    for row in value.get("artifactIdentities", []) if isinstance(row, dict)] \
        if isinstance(value.get("artifactIdentities"), list) else []
    result["providerResult"] = fields(provider, ("providerId", "providerVersion", "status", "reasons"))
    result["providerResult"]["metrics"] = fields(provider.get("metrics"), ("elapsedMillis", "cpuMillis", "peakRssBytes", "outputBytes"))
    result["providerResult"]["coverage"] = [
        fields(row, ("ruleId", "state", "reason", "required", "depth", "provenance", "certificateId", "implemented", "tested", "qualified"))
        | {"context": context(row.get("context"))}
        for row in provider.get("coverage", []) if isinstance(row, dict)
    ] if isinstance(provider.get("coverage"), list) else []
    result["providerResult"]["findings"] = [
        fields(finding, ("ruleId", "providerId", "upstreamId", "severity", "kind", "certainty", "fingerprint"))
        | {"context": context(finding.get("context")),
           "location": fields(finding.get("location"), ("path", "fileDigest", "line", "column", "endLine", "endColumn"))}
        for finding in provider.get("findings", []) if isinstance(finding, dict)
    ] if isinstance(provider.get("findings"), list) else []
    result["source_excerpts_messages_and_raw_diagnostics_omitted"] = True
    return result


def execute(args: argparse.Namespace) -> dict:
    prerequisites = preflight(args.profile)
    project_java_home = os.environ.get("JAVA_HOME")
    if not project_java_home:
        prerequisites["errors"].append("Explicit project JAVA_HOME is required for nested provider fixture execution")
        prerequisites["state"] = "unavailable"
    result = {"schema_version": 1, "evidence_kind": "executed_platform_provider_controls", "profile": args.profile,
              "started_at": utc_now(), "source": source_identity(), "state": "running", "stages": [], "errors": [],
              "prerequisites": prerequisites,
              "qualification": "Small immutable fixture adapter controls; honest incomplete coverage is not rule qualification",
              "actual_codex_host_observation": "not performed"}
    write_json(args.output, result)
    if prerequisites["state"] != "passed":
        result.update(state="unavailable", errors=prerequisites["errors"], finished_at=utc_now())
        write_json(args.output, result)
        return result
    targets = ("iosArm64", "iosSimulatorArm64") if args.profile == "ios" else ("android",)
    cases = ("positive", "missing-actual", "wrong-actual") if args.profile == "ios" else ("positive", "new-api")
    task = ":provider-native:nativeFixture" if args.profile == "ios" else ":provider-lint:lintFixture"
    for target in targets:
        for case in cases:
            name = f"provider-{target}-{case}"
            # Raw driver JSON may contain controlled fixture source excerpts. Only
            # its sanitized, validated form is added to the uploaded outer result.
            raw_result = (args.output.parent / "raw" / (name + ".json")).resolve()
            raw_result.parent.mkdir(parents=True, exist_ok=True)
            raw_result.unlink(missing_ok=True)
            arguments = [task,
                         "-Porg.gradle.java.installations.fromEnv=JAVA_HOME,JAVA_HOME_21_X64,JAVA_HOME_21_ARM64",
                         f"-PplatformFixture={(REPO_ROOT / 'fixtures/kmp').resolve()}",
                         f"-PplatformGradle={(REPO_ROOT / 'gradlew').resolve()}", f"-PplatformTarget={target}",
                         f"-PplatformCase={case}", f"-PplatformResult={raw_result}",
                         f"-PplatformJavaHome={Path(project_java_home).resolve()}"]
            stage, _ = gradle_result(arguments, log_base=args.output.parent / "raw" / name,
                                     timeout=args.timeout, require_tasks=(task,))
            stage.update(name=name, target=target, case=case)
            if raw_result.is_file():
                try:
                    value = json.loads(read_bounded(raw_result, 8 * 1024 * 1024))
                    stage["errors"].extend(validate_driver(value, args.profile, case, target))
                    if isinstance(value, dict) and isinstance(value.get("providerResult"), dict):
                        stage["driver"] = sanitized_driver(value)
                    if not stage["errors"]:
                        for artifact in value["artifactIdentities"]:
                            if digest_file(Path(artifact["path"])) != artifact["digest"]:
                                stage["errors"].append("Selected provider tool/artifact bytes differ from the driver identity")
                except (OSError, ValueError, TypeError) as error:
                    stage["errors"].append(f"Provider driver evidence could not be parsed: {type(error).__name__}")
            else:
                stage["errors"].append("Provider driver did not retain an actual result")
            stage["state"] = "passed" if not stage["errors"] else "failed"
            result["stages"].append(stage)
            result["errors"].extend(f"{name}: {error}" for error in stage["errors"])
            write_json(args.output, result)
            print(f"Platform provider {target}/{case}: {stage['state']}", flush=True)
    result["finished_at"] = utc_now()
    result["state"] = "passed" if not result["errors"] else "failed"
    write_json(args.output, result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("android", "ios"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=1200)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 2400:
        parser.error("timeout must be between 1 and 2400 seconds per bounded provider command")
    try:
        result = execute(args)
    except (OSError, ValueError, TypeError) as error:
        try:
            result = json.loads(args.output.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            result = {"schema_version": 1, "evidence_kind": "executed_platform_provider_controls", "errors": []}
        result["state"] = "failed"
        result["errors"].append(f"{type(error).__name__}: {error}")
        write_json(args.output, result)
    print(f"Platform provider controls: {result['state']} ({args.output})")
    for error in result["errors"]:
        print(error)
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())

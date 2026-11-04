"""Synthetic Native wrapper/metadata controls; never execute a JVM or Native compiler."""

import argparse
from contextlib import ExitStack, redirect_stdout
import copy
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import zipfile

try:
    from . import run_semantic_checks as checks
    from . import provision_native as native
    from . import run_platform_checks as platform_checks
    from . import run_provider_checks as provider_checks
except ImportError:
    import run_semantic_checks as checks
    import provision_native as native
    import run_platform_checks as platform_checks
    import run_provider_checks as provider_checks


IDENTITY = {"commit": "1" * 40, "dirty": False, "working_tree_digest": "sha256:" + "2" * 64}
RETAINED_JVM = checks.REPO_ROOT / "modules/semantic-facts/evidence/2026-11-04T091649Z-jvm/summary.json"
RUN = {"id": "123456", "attempt": "1", "job": "ios-native"}
# Host-native absolute SYNTHETIC selection only; this is no Apple installation
# observation. Mocking platform.system() must not change Windows Path semantics.
DEVELOPER = str(checks.REPO_ROOT / "synthetic-Xcode_26.4.1.app/Contents/Developer")


def fixture_symlink(test_case, link, target, *, target_is_directory=False):
    """Real owned fixture IO only; one known Windows privilege gap is a skip."""
    try:
        link.symlink_to(target, target_is_directory=target_is_directory)
    except OSError as error:
        if os.name == "nt" and getattr(error, "winerror", None) == 1314:
            test_case.skipTest("Actual Windows symlink fixture unavailable: ERROR_PRIVILEGE_NOT_HELD; synthetic link rejection still runs")
        raise


def hosted_environment(java_home, data_dir):
    return {"JAVA_HOME": str(java_home), "KONAN_DATA_DIR": str(data_dir), "KONAN_USE_INTERNAL_SERVER": "0",
            "DEVELOPER_DIR": DEVELOPER, "GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted",
            "RUNNER_OS": "macOS", "RUNNER_ARCH": "ARM64", "GITHUB_RUN_ID": RUN["id"],
            "GITHUB_RUN_ATTEMPT": RUN["attempt"], "GITHUB_JOB": RUN["job"]}


def jvm_summary():
    return checks.strict_json(RETAINED_JVM.read_bytes())


def native_summary():
    # Historical public JVM metadata is reused ONLY to exercise the validator.
    # Replacing labels/digests cannot establish Native compiler execution.
    value = jvm_summary()
    value.update(mode="native", target="iosSimulatorArm64", testedCommit=IDENTITY["commit"])
    value["runner"] = {"os": "Mac OS X", "architecture": "aarch64", "fixtureHostJava": "21.0.13",
                       "projectJavaVersion": "25.0.5", "projectJavaRuntimeVersion": "25.0.5+9", "projectJavaVendor": "Synthetic fixture"}
    value["artifacts"] = [row for row in value["artifacts"] if not row["coordinate"].startswith(("selected-kotlin-compiler:", "fixture-dependency:"))]
    for coordinate in ("selected-native-2.4.21:bin/konanc", "selected-native-2.4.21:konan/konan.properties",
                       "selected-native-2.4.21:konan/lib/kotlin-native-compiler-embeddable.jar", "fixture-dependency:klib:iosSimulatorArm64"):
        value["artifacts"].append({"coordinate": coordinate, "digest": "sha256:" + "3" * 64, "omittedCoordinateDigest": None})
    return value


def native_tree(data_dir, name="kotlin-native-prebuilt-macos-aarch64-2.4.21", version="2.4.21"):
    home = data_dir / name
    for relative in ("bin", "tools", "konan/lib", "klib/common/stdlib/default", "klib/platform/ios_simulator_arm64",
                     "klib/platform/ios_arm64"):
        (home / relative).mkdir(parents=True, exist_ok=True)
    (home / "bin/konanc").write_bytes(b"synthetic launcher; never execute")
    (home / "bin/konanc").chmod(0o755)
    (home / "bin/run_konan").write_bytes(b"synthetic dispatcher; never execute")
    (home / "bin/run_konan").chmod(0o755)
    (home / "tools/env_blacklist").write_bytes(b"synthetic env input")
    (home / "konan/konan.properties").write_text(
        "targetSysRoot.ios_simulator_arm64 = target-sysroot-xcode_26.4_17E192-iphonesimulator\n"
        "llvmHome.macos_arm64 = $llvm.macos_arm64.dev\nllvm.macos_arm64.dev = synthetic-llvm-19\n"
        "libffiDir.macos_arm64 = libffi-3.3-1-macos-arm64\n")
    with zipfile.ZipFile(home / "konan/lib/kotlin-native-compiler-embeddable.jar", "w") as jar:
        jar.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\r\nImplementation-Version: {version}\r\n\r\n")
    (home / "klib/common/stdlib/default/manifest").write_text(
        "unique_name=stdlib\nbuiltins_platform=NATIVE\nnative_targets=ios_arm64 ios_simulator_arm64\n")
    for target in ("ios_arm64", "ios_simulator_arm64"):
        manifest = home / f"klib/platform/{target}/org.jetbrains.kotlin.native.platform.Synthetic/default/manifest"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(f"unique_name=org.jetbrains.kotlin.native.platform.Synthetic\nbuiltins_platform=NATIVE\nnative_targets={target}\n")
    return home


def setup_attempt(root):
    # Canonicalize only the caller-trusted outer fixture root. Production still
    # checks every selected descendant/path/identity and rejects traversal.
    root = root.resolve(strict=True)
    argument = argparse.Namespace(output=root / "build/ci-evidence/ios-semantic.json",
                                  environment=root / "build/ci-evidence/environment.json", target="iosSimulatorArm64", timeout=1,
                                  native_selection=root / "build/ci-evidence/raw/native-provisioning/selection.json",
                                  native_provisioning=root / "build/ci-evidence/native.json")
    java_home = root / "synthetic-jdk"
    for relative in ("bin/java", "release", "lib/modules"):
        path = java_home / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"synthetic JDK input; never execute")
    (java_home / "bin/java").chmod(0o755)
    (java_home / "release").write_text('JAVA_VERSION="25.0.5"\nJAVA_RUNTIME_VERSION="25.0.5+9"\nIMPLEMENTOR="Synthetic fixture"\n')
    data_dir = argument.native_selection.parent / "data"
    home = native_tree(data_dir)
    plugin = root / "modules/semantic-facts/build/libs/semantic-facts-0.1.0-dev.1-kotlin-2.4.21-plugin.jar"
    plugin.parent.mkdir(parents=True, exist_ok=True)
    plugin.write_bytes(b"synthetic plugin input; never execute")
    environment = {"schema_version": 1, "evidence_kind": "observed_environment", "state": "failed", "source": IDENTITY,
                   "host": {"os": "Darwin", "arch": "arm64"}, "tool_paths": {"DEVELOPER_DIR": DEVELOPER},
                   "ci": {"GITHUB_RUN_ID": RUN["id"], "GITHUB_RUN_ATTEMPT": RUN["attempt"], "GITHUB_JOB": RUN["job"]},
                   "java": {"major": 25, "path": str(java_home / "bin/java"),
                            "launcher_digest": checks.digest_file(java_home / "bin/java"),
                            "release_file_digest": checks.digest_file(java_home / "release"),
                            "release_properties": {"JAVA_VERSION": "25.0.5", "JAVA_RUNTIME_VERSION": "25.0.5+9", "IMPLEMENTOR": "Synthetic fixture"}},
                   "xcode": {"state": "observed", "version": "26.4.1", "build": "17E202", "iphoneos_sdk": "26.4", "iphonesimulator_sdk": "26.4",
                             "available_simulator_runtimes": []}, "android": {"compile_sdk_37_installed": False}, "gradle": {"state": "pinned"}}
    argument.environment.parent.mkdir(parents=True, exist_ok=True)
    argument.environment.write_text(json.dumps(environment))
    enroll_synthetic(argument, java_home, data_dir)
    value = native_summary()
    for row in value["artifacts"]:
        coordinate = row["coordinate"]
        if coordinate.startswith("selected-native-2.4.21:"):
            row["digest"] = checks.digest_file(home / coordinate.split(":", 1)[1])
        elif coordinate.startswith("selected-semantic-jdk:"):
            row["digest"] = checks.digest_file(java_home / coordinate.split(":", 1)[1])
        elif coordinate == "ktchecker:kotlin-fir-2.4.21/v1":
            row["digest"] = checks.digest_file(plugin)
    return argument, java_home, data_dir, value


def enroll_synthetic(argument, java_home, data_dir, targets=("iosArm64", "iosSimulatorArm64")):
    """Metadata controls only: no acquired distribution or Native launcher."""
    home = data_dir / native.HOME_NAME
    inventory = native.component_inventory(home, targets)
    selected_xcode = {"version": "26.4.1", "build": "17E202", **{key: {"state": "observed", "version": "26.4"}
                      for key in ("iphoneos_sdk", "iphonesimulator_sdk")}}
    archive = argument.native_selection.parent / "archive.tar.gz"
    archive.write_bytes(b"SYNTHETIC archive; never claim official acquisition")
    selection = {"schema_version": 1, "evidence_kind": "private_native_selection", "source": IDENTITY, "run": RUN,
                 "targets": list(targets), "native_home": str(home), "konan_data_dir": str(data_dir), "java_home": str(java_home),
                 "developer_dir": DEVELOPER, "archive_path": str(archive), "selected_xcode": selected_xcode,
                 "components": inventory, "inventory_digest": native.canonical_digest(inventory),
                 "java_components": native.java_inventory(java_home)}
    native.write_json(argument.native_selection, selection)
    receipt = {"schema_version": 1, "evidence_kind": "native_distribution_provisioning", "state": "passed", "source": IDENTITY,
               "run": RUN, "selected_targets": list(targets), "selection_digest": native.digest_file(argument.native_selection),
               "tuple_qualified": False, "custom_rules_qualified": False, "release_qualified": False,
               "archive_digest": "sha256:" + native.ARCHIVE_SHA256, "archive_size": native.ARCHIVE_SIZE,
               "checksum_provenance": native.CHECKSUM_PROVENANCE, "archive_verified": True, "compiler_version_observed": native.VERSION,
               "inventory_digest": native.canonical_digest(inventory)}
    native.write_json(argument.native_provisioning, receipt)


class SemanticMetadataTests(unittest.TestCase):
    def validate(self, value):
        java = {key: value["runner"][key] for key in ("projectJavaVersion", "projectJavaRuntimeVersion", "projectJavaVendor")}
        return checks.validate_summary(value, value["mode"], value["target"], value["testedCommit"], java)

    def test_retained_actual_jvm_summary_matches_metadata_contract_not_native_evidence(self):
        value = jvm_summary()
        self.assertEqual([], self.validate(value))
        self.assertEqual("jvm", value["mode"])
        self.assertEqual(4, len(value["cases"]))
        self.assertEqual(5, len(value["executions"]))
        self.assertFalse(value["tupleQualified"])
        self.assertFalse(value["customRulesQualified"])

    def test_synthetic_native_metadata_shape_is_valid_but_is_not_execution(self):
        self.assertEqual([], self.validate(native_summary()))

    def test_empty_passing_summary_missing_dependency_and_duplicate_labels_fail(self):
        for alteration in ("empty", "dependency", "duplicate_case", "duplicate_execution", "compiler_artifact"):
            value = native_summary()
            if alteration == "empty":
                value["cases"], value["executions"] = [], []
            elif alteration == "dependency":
                value["executions"] = [row for row in value["executions"] if row["label"] != "dependency"]
            elif alteration == "duplicate_case":
                value["cases"][1] = copy.deepcopy(value["cases"][0])
            elif alteration == "duplicate_execution":
                value["executions"][1] = copy.deepcopy(value["executions"][0])
            else:
                value["artifacts"] = [row for row in value["artifacts"] if "kotlin-native-compiler-embeddable.jar" not in row["coordinate"]]
            with self.subTest(alteration=alteration):
                self.assertTrue(self.validate(value))

    def test_versions_jvm_substitution_scope_qualification_and_booleans_are_exact(self):
        for alteration in ("version", "host", "jdk", "target", "tuple", "custom", "schema", "assertion", "count", "metric", "negative_exit"):
            value = native_summary()
            if alteration == "version":
                value["cases"][0]["observedCompilerVersions"] = ["2.4.20"]
            elif alteration == "host":
                value["runner"]["os"] = "Linux"
            elif alteration == "jdk":
                value["runner"]["fixtureHostJava"] = "25.0.5"
            elif alteration == "target":
                value["target"] = "jvm"
            elif alteration in {"tuple", "custom"}:
                value["tupleQualified" if alteration == "tuple" else "customRulesQualified"] = True
            elif alteration == "schema":
                value["schemaVersion"] = True
            elif alteration == "assertion":
                value["cases"][0]["assertions"]["native_is_not_jvm_substitution"] = False
            elif alteration == "count":
                value["cases"][0]["counts"]["shards"] = True
            elif alteration == "metric":
                value["executions"][0]["metrics"]["outputBytes"] = True
            else:
                value["cases"][1]["compilerExitCode"] = 0
            with self.subTest(alteration=alteration):
                self.assertTrue(self.validate(value))

    def test_unknown_metadata_paths_symbols_reasons_and_raw_commands_are_not_projected(self):
        value = native_summary()
        value["source"] = "PRIVATE source text"
        value["limitations"] = ["PRIVATE raw command"]
        value["runner"]["DEVELOPER_DIR"] = "PRIVATE machine path"
        value["runner"]["ImageVersion"] = "PRIVATE/unsafe/path"
        value["artifacts"][0]["coordinate"] = "PRIVATE/absolute/source/secret.kt"
        value["cases"][0]["assertions"]["PRIVATE symbol"] = True
        value["cases"][0]["reasons"] = ["PRIVATE source", "private_alphanumeric_secret"]
        value["cases"][0]["unknownReasonCounts"] = {"PRIVATE symbol": 2}
        value["executions"][0]["command"] = ["PRIVATE raw command"]
        projection = checks.metadata_projection(value, "native", "iosSimulatorArm64")
        encoded = json.dumps(projection)
        self.assertNotIn("PRIVATE", encoded)
        self.assertNotIn("private_alphanumeric_secret", encoded)
        self.assertIn("unrecognized_reason_omitted", encoded)
        self.assertTrue(self.validate(value))

    def test_unsafe_coordinates_wrong_target_and_duplicate_component_identities_fail(self):
        for coordinate in ("selected-native-2.4.21:../../secret", "selected-native-2.4.21:konan/../secret", "selected-native-2.4.21:konan/C:secret",
                           "selected-native-2.4.21:klib/platform/ios_arm64/stdlib", "selected-kotlin-compiler:kotlin-compiler-embeddable-2.4.21.jar"):
            value = native_summary()
            value["artifacts"].append({"coordinate": coordinate, "digest": "sha256:" + "4" * 64, "omittedCoordinateDigest": None})
            with self.subTest(coordinate=coordinate):
                self.assertTrue(self.validate(value))
        value = native_summary()
        value["artifacts"].append(copy.deepcopy(value["artifacts"][0]))
        self.assertTrue(self.validate(value))


class NativeDiscoveryTests(unittest.TestCase):
    def test_exact_explicit_mac_aarch64_package_and_manifest_version_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            data_dir = Path(temporary).resolve(strict=True)
            home = native_tree(data_dir)
            native.validate_home(home, ("iosSimulatorArm64",))
            alias = native_tree(data_dir, "kotlin-native-prebuilt-macos-arm64-2.4.21")
            with self.assertRaises(ValueError):
                native.validate_home(alias, ("iosSimulatorArm64",))

    def test_wrong_arch_old_version_missing_target_and_duplicate_manifest_do_not_pass(self):
        for alteration in ("architecture", "version", "target", "manifest"):
            with tempfile.TemporaryDirectory() as temporary:
                data_dir = Path(temporary).resolve(strict=True)
                home = native_tree(data_dir, "kotlin-native-prebuilt-macos-x86_64-2.4.21" if alteration == "architecture" else
                                   "kotlin-native-prebuilt-macos-aarch64-2.4.21", "2.4.20" if alteration == "version" else "2.4.21")
                if alteration == "target":
                    shutil.rmtree(home / "klib/platform/ios_simulator_arm64")
                elif alteration == "manifest":
                    with zipfile.ZipFile(home / "konan/lib/kotlin-native-compiler-embeddable.jar", "w") as jar:
                        jar.writestr("META-INF/MANIFEST.MF", "Implementation-Version: 2.4.21\nImplementation-Version: 2.4.20\n")
                with self.subTest(alteration=alteration), self.assertRaises(ValueError):
                    native.validate_home(home, ("iosSimulatorArm64",))

    def test_native_cached_prerequisites_do_not_equate_configured_sysroot_with_host_xcode(self):
        with tempfile.TemporaryDirectory() as temporary:
            data_dir = Path(temporary).resolve(strict=True)
            home = native_tree(data_dir)
            (data_dir / "dependencies/synthetic-llvm-19").mkdir(parents=True)
            result = checks.dependency_inventory(home, data_dir, "iosSimulatorArm64", "17E202")
            self.assertTrue(result["autodownload_disabled"])
            self.assertFalse(result["external_dependency_closure_qualified"])
            sysroot = next(row for row in result["dependencies"] if row["kind"] == "target_sysroot")
            self.assertEqual("17E192", sysroot["configured_xcode_build"])
            self.assertEqual("17E202", sysroot["selected_host_xcode_build"])
            self.assertFalse(sysroot["configured_build_matches_selected_host"])
            self.assertFalse(sysroot["cache_entry_present"])
            self.assertTrue(next(row for row in result["dependencies"] if row["kind"] == "llvm")["cache_entry_present"])

    def test_unrecognized_configuration_package_text_is_not_uploaded_or_followed(self):
        with tempfile.TemporaryDirectory() as temporary:
            data_dir = Path(temporary).resolve(strict=True)
            home = native_tree(data_dir)
            (home / "konan/konan.properties").write_text("llvmHome.macos_arm64 = ../../PRIVATE secret\n")
            result = checks.dependency_inventory(home, data_dir, "iosSimulatorArm64", "17E202")
            self.assertNotIn("PRIVATE", json.dumps(result))
            self.assertTrue(all(not row["cache_entry_present"] for row in result["dependencies"]))


class SemanticWrapperTests(unittest.TestCase):
    def run_attempt(self, root, argument, java_home, data_dir, summary, command_state="passed", write_summary=True):
        def command(arguments, **kwargs):
            self.assertIn("-PsemanticMode=native", arguments)
            self.assertIn("-PsemanticTarget=iosSimulatorArm64", arguments)
            self.assertIn(f"-PsemanticNativeHome={data_dir / native.HOME_NAME}", arguments)
            self.assertIn(f"-PsemanticJavaHome={java_home.resolve()}", arguments)
            self.assertEqual((checks.TASK,), kwargs["require_tasks"])
            self.assertEqual(12, len(kwargs["additional_output_paths"]))
            private = argument.output.parent / "raw/native-semantic-driver"
            raw = b"PRIVATE raw facts, source, symbols and compiler commands"
            (private / "results.json").write_bytes(raw)
            summary["privateEvidenceDigest"] = checks.digest_bytes(raw)
            if write_summary:
                (private / "summary.json").write_text(json.dumps(summary))
            return {"state": command_state, "return_code": 0 if command_state == "passed" else 1,
                    "tasks": {checks.TASK: "EXECUTED" if command_state == "passed" else "FAILED"},
                    "command": ["PRIVATE command"], "raw_logs_uploaded": False}, "PRIVATE Gradle transcript"
        with patch.object(checks, "source_identity", return_value=IDENTITY), \
                patch.object(checks.platform, "system", return_value="Darwin"), \
                patch.object(checks.platform, "machine", return_value="arm64"), \
                patch.dict(os.environ, hosted_environment(java_home, data_dir), clear=True), \
                patch.object(native, "verify_archive"), \
                patch.object(checks, "gradle_result", side_effect=command) as gradle:
            result = checks.run(argument, root)
        return result, gradle

    def test_successful_synthetic_wrapper_is_metadata_only_and_independent_of_android_runtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            argument, java_home, data_dir, summary = setup_attempt(root)
            result, gradle = self.run_attempt(root, argument, java_home, data_dir, summary)
            gradle.assert_called_once()
            self.assertEqual("passed", result["state"])
            self.assertEqual(5, result["compiler_execution_count"])
            self.assertTrue(result["private_evidence_digest_verified"])
            self.assertFalse(result["native_distribution_downloaded_by_wrapper"])
            self.assertFalse(result["android_required_for_this_gate"])
            self.assertFalse(result["simulator_runtime_required_for_this_gate"])
            self.assertNotIn("PRIVATE", json.dumps(result))
            self.assertNotIn(str(root), json.dumps(result))

    def test_actual_trusted_outer_fixture_alias_enrolls_only_canonical_selected_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            outer = Path(temporary).resolve(strict=True)
            root = outer / "checkout"
            root.mkdir()
            alias = outer / "trusted-outer-alias"
            fixture_symlink(self, alias, root, target_is_directory=True)
            argument, java_home, data_dir, summary = setup_attempt(alias)
            self.assertEqual(root / "synthetic-jdk", java_home)
            self.assertEqual(root / "build/ci-evidence/raw/native-provisioning/data", data_dir)
            selection = json.loads(argument.native_selection.read_bytes())
            self.assertEqual(str(data_dir / native.HOME_NAME), selection["native_home"])
            self.assertEqual(str(data_dir), selection["konan_data_dir"])
            self.assertEqual(str(java_home), selection["java_home"])
            self.assertEqual(str(argument.native_selection.parent / "archive.tar.gz"), selection["archive_path"])
            result, gradle = self.run_attempt(alias, argument, java_home, data_dir, summary)
            gradle.assert_called_once()
            self.assertEqual("passed", result["state"])
            self.assertFalse(result["tuple_qualified"])

    def test_failed_dependency_attempt_preserved_not_fabricated_empty_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            argument, java_home, data_dir, summary = setup_attempt(root)
            summary.update(state="failed", cases=[], reasons=["semantic_fixture_driver_failed"])
            summary["executions"] = [dict(summary["executions"][0], exitCode=1)]
            result, _ = self.run_attempt(root, argument, java_home, data_dir, summary, "failed")
            self.assertEqual("failed", result["state"])
            self.assertEqual(1, result["compiler_execution_count"])
            self.assertEqual(1, result["driver_summary"]["executions"][0]["exitCode"])
            self.assertFalse(result["tuple_qualified"])
            self.assertNotIn("PRIVATE", json.dumps(result))

    def test_genuine_unavailable_driver_shape_remains_unavailable_with_no_compiler_claim(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            argument, java_home, data_dir, summary = setup_attempt(root)
            summary.update(state="unavailable", cases=[], executions=[], reasons=["semantic_native_distribution_incomplete"])
            result, _ = self.run_attempt(root, argument, java_home, data_dir, summary, "failed")
            self.assertEqual("unavailable", result["state"])
            self.assertEqual(0, result["compiler_execution_count"])
            self.assertTrue(result["gradle_attempt_executed"])

    def test_missing_summary_or_false_assertion_never_passes(self):
        for summary_present in (False, True):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve(strict=True)
                argument, java_home, data_dir, summary = setup_attempt(root)
                summary["cases"][0]["assertions"]["native_is_not_jvm_substitution"] = False
                result, _ = self.run_attempt(root, argument, java_home, data_dir, summary, write_summary=summary_present)
                with self.subTest(summary_present=summary_present):
                    self.assertEqual("failed", result["state"])

    def test_unsafe_or_nonfresh_private_output_stops_before_any_gradle_attempt(self):
        for failure in ("outside_build", "traversal", "nonfresh"):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve(strict=True)
                argument, java_home, data_dir, summary = setup_attempt(root)
                if failure == "outside_build":
                    argument.output = root / "outside/ios-semantic.json"
                elif failure == "traversal":
                    argument.output = root / "build/../outside/ios-semantic.json"
                else:
                    private = argument.output.parent / "raw/native-semantic-driver"
                    private.mkdir(parents=True)
                    (private / "results.json").write_bytes(b"prior synthetic result must not be reused")
                result, gradle = self.run_attempt(root, argument, java_home, data_dir, summary)
                with self.subTest(failure=failure):
                    gradle.assert_not_called()
                    self.assertEqual("unavailable", result["state"])
                    self.assertEqual(0, result["compiler_execution_count"])
                    self.assertFalse(result["gradle_attempt_executed"])

    def test_wrong_host_or_selected_jvm_digest_stops_before_any_gradle_call(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            argument, java_home, data_dir, summary = setup_attempt(root)
            with patch.object(checks, "source_identity", return_value=IDENTITY), \
                    patch.object(checks.platform, "system", return_value="Windows"), patch.object(checks, "gradle_result") as gradle:
                result = checks.run(argument, root)
            gradle.assert_not_called()
            self.assertEqual("unavailable", result["state"])
            environment = json.loads(argument.environment.read_text())
            environment["java"]["launcher_digest"] = "sha256:" + "f" * 64
            argument.environment.write_text(json.dumps(environment))
            with patch.object(checks, "source_identity", return_value=IDENTITY), \
                    patch.object(checks.platform, "system", return_value="Darwin"), patch.object(checks.platform, "machine", return_value="arm64"), \
                    patch.dict(os.environ, hosted_environment(java_home, data_dir), clear=True), patch.object(checks, "gradle_result") as gradle:
                result = checks.run(argument, root)
            gradle.assert_not_called()
            self.assertEqual("unavailable", result["state"])


class NativePrerequisiteDiagnosticsTests(unittest.TestCase):
    def test_each_observed_prerequisite_failure_has_a_fixed_before_launch_code(self):
        alterations = {
            "host_os": "native_host_os_unavailable", "host_arch": "native_host_arch_unavailable",
            "context": "native_hosted_context_required", "run_context": "native_hosted_run_binding_unavailable",
            "missing_environment": "native_environment_missing", "invalid_environment": "native_environment_invalid_or_unsafe",
            "schema": "native_environment_invalid_or_unsafe", "source": "native_environment_source_mismatch",
            "run": "native_environment_run_mismatch", "observed_host": "native_observed_host_mismatch",
            "java_unobserved": "native_java_unobserved", "java_major": "native_java_major_mismatch",
            "xcode_unobserved": "native_xcode_unobserved", "xcode_version": "native_xcode_version_mismatch",
            "xcode_build": "native_xcode_build_mismatch", "gradle": "native_gradle_not_pinned",
            "java_home": "native_java_home_missing_or_unsafe", "java_missing": "native_java_distribution_incomplete_or_unsafe",
            "java_unexecutable": "native_java_launcher_unexecutable", "java_changed": "native_selected_java_changed",
            "java_release": "native_java_release_mismatch", "developer": "native_xcode_selection_changed_or_unavailable",
        }
        for alteration, code in alterations.items():
            with self.subTest(alteration=alteration), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve(strict=True)
                argument, java_home, data_dir, _ = setup_attempt(root)
                environment = json.loads(argument.environment.read_text())
                child_env = hosted_environment(java_home, data_dir)
                system, architecture = "Darwin", "arm64"
                if alteration == "host_os":
                    system = "Linux"
                elif alteration == "host_arch":
                    architecture = "x86_64"
                elif alteration == "context":
                    child_env["GITHUB_ACTIONS"] = "false"
                elif alteration == "run_context":
                    child_env["GITHUB_RUN_ID"] = "PRIVATE/run"
                elif alteration == "schema":
                    environment["schema_version"] = True
                elif alteration == "source":
                    environment["source"] = dict(IDENTITY, working_tree_digest="sha256:" + "f" * 64)
                elif alteration == "run":
                    environment["ci"]["GITHUB_RUN_ID"] = "456789"
                elif alteration == "observed_host":
                    environment["host"]["arch"] = "x86_64"
                elif alteration == "java_unobserved":
                    environment["java"]["major"] = None
                elif alteration == "java_major":
                    environment["java"]["major"] = 21
                elif alteration == "xcode_unobserved":
                    environment["xcode"]["state"] = "unavailable"
                elif alteration == "xcode_version":
                    environment["xcode"]["version"] = "26.4"
                elif alteration == "xcode_build":
                    environment["xcode"]["build"] = "17E192"
                elif alteration == "gradle":
                    environment["gradle"]["state"] = "unpinned"
                elif alteration == "java_home":
                    child_env.pop("JAVA_HOME")
                elif alteration == "java_missing":
                    (java_home / "lib/modules").unlink()
                elif alteration == "java_unexecutable":
                    # Windows chmod/access cannot emulate removing POSIX
                    # executable bits. Inject ONLY this selected denial.
                    denied_access = Mock(return_value=False)
                elif alteration == "java_changed":
                    environment["java"]["launcher_digest"] = "sha256:" + "f" * 64
                elif alteration == "java_release":
                    environment["java"]["release_properties"]["JAVA_VERSION"] = "26.0.1"
                elif alteration == "developer":
                    child_env["DEVELOPER_DIR"] = "/PRIVATE/OtherXcode.app/Contents/Developer"
                argument.environment.write_text(json.dumps(environment))
                if alteration == "missing_environment":
                    argument.environment.unlink()
                elif alteration == "invalid_environment":
                    argument.environment.write_text('{"PRIVATE malformed source":')
                with patch.object(checks, "source_identity", return_value=IDENTITY), \
                        patch.object(native.platform, "system", return_value=system), \
                        patch.object(native.platform, "machine", return_value=architecture), \
                        patch.dict(os.environ, child_env, clear=True), \
                        patch.object(native, "run_logged") as launcher, patch.object(native, "verify_archive"), \
                        patch.object(checks, "gradle_result") as gradle, ExitStack() as stack:
                    if alteration == "java_unexecutable":
                        facade = SimpleNamespace(environ=os.environ, X_OK=os.X_OK, access=denied_access)
                        stack.enter_context(patch.object(native, "os", facade))
                    result = checks.run(argument, root)
                if alteration == "java_unexecutable":
                    denied_access.assert_called_once_with(java_home / "bin/java", os.X_OK)
                self.assertEqual([code], result["errors"])
                self.assertEqual("unavailable", result["state"])
                self.assertEqual(0, result["compiler_execution_count"])
                self.assertFalse(result["gradle_attempt_executed"])
                gradle.assert_not_called()
                launcher.assert_not_called()
                self.assertNotIn("PRIVATE", json.dumps(result))

    def test_each_selection_failure_is_explicit_and_never_falls_back_to_ambient_home(self):
        alterations = {
            "selection_missing": "native_explicit_selection_missing", "receipt_missing": "native_provisioning_receipt_missing",
            "failed_provisioning": "native_provisioning_did_not_pass", "target": "native_selection_target_mismatch",
            "stale_run": "native_selection_source_or_run_mismatch", "unsafe_path": "native_evidence_path_unsafe",
            "receipt_digest": "native_provisioning_receipt_identity_invalid", "ambient_data": "native_explicit_selected_paths_or_environment_changed",
            "internal_server": "native_explicit_selected_paths_or_environment_changed", "component": "native_selected_components_changed",
            "java_modules": "native_enrolled_java_components_changed", "target_klib": "native_target_klib_missing",
            "compiler_version": "native_compiler_version_mismatch",
        }
        for alteration, code in alterations.items():
            with self.subTest(alteration=alteration), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve(strict=True)
                argument, java_home, data_dir, _ = setup_attempt(root)
                selection = json.loads(argument.native_selection.read_text())
                receipt = json.loads(argument.native_provisioning.read_text())
                child_env = hosted_environment(java_home, data_dir)
                if alteration == "selection_missing":
                    argument.native_selection.unlink()
                elif alteration == "receipt_missing":
                    argument.native_provisioning.unlink()
                elif alteration == "failed_provisioning":
                    receipt.update(state="failed", errors=["native_archive_transport_failed", "PRIVATE/raw"])
                elif alteration == "target":
                    selection["targets"] = ["iosArm64"]
                elif alteration == "stale_run":
                    selection["run"] = dict(RUN, attempt="2")
                elif alteration == "unsafe_path":
                    argument.native_selection = root / "build/../PRIVATE/selection.json"
                elif alteration == "receipt_digest":
                    receipt["selection_digest"] = "sha256:" + "f" * 64
                elif alteration == "ambient_data":
                    child_env["KONAN_DATA_DIR"] = str(root / "ambient-konan")
                    native_tree(root / "ambient-konan")
                elif alteration == "internal_server":
                    child_env["KONAN_USE_INTERNAL_SERVER"] = "auto"
                elif alteration == "component":
                    (data_dir / native.HOME_NAME / "tools/env_blacklist").write_text("PRIVATE changed bytes")
                elif alteration == "java_modules":
                    (java_home / "lib/modules").write_text("PRIVATE changed JDK modules")
                elif alteration == "target_klib":
                    shutil.rmtree(data_dir / native.HOME_NAME / "klib/platform/ios_simulator_arm64")
                elif alteration == "compiler_version":
                    with zipfile.ZipFile(data_dir / native.HOME_NAME / "konan/lib/kotlin-native-compiler-embeddable.jar", "w") as jar:
                        jar.writestr("META-INF/MANIFEST.MF", "Implementation-Version: 2.4.20\n")
                if alteration in {"target", "stale_run"}:
                    native.write_json(argument.native_selection, selection)
                    receipt["selection_digest"] = native.digest_file(argument.native_selection)
                if alteration != "receipt_missing":
                    native.write_json(argument.native_provisioning, receipt)
                with patch.object(checks, "source_identity", return_value=IDENTITY), \
                        patch.object(native.platform, "system", return_value="Darwin"), \
                        patch.object(native.platform, "machine", return_value="arm64"), \
                        patch.dict(os.environ, child_env, clear=True), patch.object(native, "verify_archive"), \
                        patch.object(native, "run_logged") as launcher, patch.object(checks, "gradle_result") as gradle:
                    result = checks.run(argument, root)
                self.assertEqual([code], result["errors"])
                self.assertEqual("unavailable", result["state"])
                gradle.assert_not_called()
                launcher.assert_not_called()
                self.assertNotIn("PRIVATE", json.dumps(result))

    def test_unobserved_sdk_inventory_is_not_invented_as_failure_or_runtime_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            argument, java_home, data_dir, _ = setup_attempt(root)
            value = json.loads(argument.environment.read_text())
            value["xcode"].pop("iphoneos_sdk")
            value["xcode"]["iphonesimulator_sdk"] = "PRIVATE raw command/path"
            argument.environment.write_text(json.dumps(value))
            with patch.object(native.platform, "system", return_value="Darwin"), \
                    patch.object(native.platform, "machine", return_value="arm64"), \
                    patch.dict(os.environ, hosted_environment(java_home, data_dir), clear=True):
                _, _, selected = native.observed_prerequisites(argument.environment, root, IDENTITY)
            self.assertEqual({"state": "unobserved", "version": None}, selected["iphoneos_sdk"])
            self.assertEqual({"state": "unobserved", "version": None}, selected["iphonesimulator_sdk"])
            self.assertNotIn("PRIVATE", json.dumps(selected))


def synthetic_archive(entries):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        for name, kind, payload in entries:
            info = tarfile.TarInfo(name)
            info.mode = 0o755
            info.type = kind
            if kind == tarfile.REGTYPE:
                info.size = len(payload)
                archive.addfile(info, io.BytesIO(payload))
            else:
                info.linkname = payload
                archive.addfile(info)
    return buffer.getvalue()


def synthetic_raw_tar_member(info, payload=b"", *, format=tarfile.USTAR_FORMAT):
    """Small raw headers exercise the real parser, not direct frombuf calls."""
    return info.tobuf(format=format) + payload + b"\0" * (-len(payload) % tarfile.BLOCKSIZE)


def synthetic_pax_archive(headers, payload=b"x"):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz", format=tarfile.PAX_FORMAT) as archive:
        info = tarfile.TarInfo(native.HOME_NAME + "/synthetic-pax-file")
        info.size, info.pax_headers = len(payload), headers
        archive.addfile(info, io.BytesIO(payload))
    return buffer.getvalue()


class NativeArchiveControlsTests(unittest.TestCase):
    def extract(self, root, entries):
        archive = root / "synthetic.tar.gz"
        archive.write_bytes(synthetic_archive(entries))
        return native.extract_archive(archive, root / "staging")

    def inspect_headers(self, body, budget=None):
        budget = native.ExtractionBudget() if budget is None else budget
        with io.BytesIO(body) as raw, gzip.GzipFile(
                fileobj=native.BoundedReader(raw, budget, compressed=True), mode="rb") as uncompressed, \
                native.BoundedTarFile.open(fileobj=native.BoundedReader(uncompressed, budget, compressed=False),
                                           mode="r|", tarinfo=native.BoundedTarInfo, native_budget=budget) as archive:
            return list(archive), budget

    def test_fixed_official_pin_has_author_not_upstream_sha256_provenance(self):
        self.assertEqual(290160802, native.ARCHIVE_SIZE)
        self.assertEqual("bfa7a92e6654ac8f060648a2937ec08f8d4b1c49", native.ARCHIVE_SHA1)
        self.assertEqual("bf4edb526d91e17d0862102f69250525d788e79011f86c2c64362749adf260ae", native.ARCHIVE_SHA256)
        self.assertEqual(native.ARCHIVE_URL + ".sha1", native.CHECKSUM_PROVENANCE["published_checksum_url"])
        self.assertEqual(404, native.CHECKSUM_PROVENANCE["published_sha256_http_status_at_enrollment"])
        self.assertEqual(404, native.CHECKSUM_PROVENANCE["published_sha512_http_status_at_enrollment"])
        self.assertFalse(native.CHECKSUM_PROVENANCE["signature_verified"])
        self.assertEqual(384 * 1024 * 1024, native.MAX_ARCHIVE_BYTES)
        self.assertEqual(4 * 1024 * 1024 * 1024, native.MAX_EXTRACTED_BYTES)
        self.assertEqual(100000, native.MAX_MEMBERS)
        self.assertEqual(16, native.MAX_EXTENSION_DEPTH)
        self.assertEqual(1700000, native.MAX_RAW_HEADERS)
        self.assertEqual(native.MAX_MEMBERS * (native.MAX_EXTENSION_DEPTH + 1), native.MAX_RAW_HEADERS)
        self.assertEqual(512 * 1024 * 1024, native.MAX_FILE_BYTES)
        self.assertEqual(64 * 1024 * 1024, native.MAX_METADATA_BYTES)
        self.assertEqual(4464476160, native.MAX_TAR_STREAM_BYTES)
        self.assertEqual(1024 * 1024, native.MAX_READ_BYTES)
        self.assertEqual(64, native.MAX_PATH_COMPONENTS)
        self.assertEqual(20000, native.MAX_COMPONENTS)
        self.assertEqual(300, native.EXTRACTION_TIMEOUT_SECONDS)

    def test_portable_synthetic_regular_file_and_hard_link_extract_exact_owned_bytes(self):
        entries = [(native.HOME_NAME + "/lib/regular", tarfile.REGTYPE, b"synthetic bytes"),
                   (native.HOME_NAME + "/lib/hard", tarfile.LNKTYPE, native.HOME_NAME + "/lib/regular")]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            result = self.extract(root, entries)
            self.assertEqual(2, result["members"])
            self.assertEqual(result["members"], result["logical_members"])
            self.assertEqual(2, result["headers"])
            self.assertEqual(native.MAX_RAW_HEADERS, result["max_raw_headers"])
            self.assertEqual(1, result["links"])
            self.assertEqual(len(b"synthetic bytes"), result["extracted_regular_bytes"])
            for name in ("regular", "hard"):
                self.assertEqual(b"synthetic bytes", (root / "staging" / native.HOME_NAME / "lib" / name).read_bytes())

    def test_actual_synthetic_archive_safe_links_target_only_owned_regular_files(self):
        entries = [(native.HOME_NAME + "/lib/regular", tarfile.REGTYPE, b"synthetic bytes"),
                   (native.HOME_NAME + "/lib/symbolic", tarfile.SYMTYPE, "regular"),
                   (native.HOME_NAME + "/lib/hard", tarfile.LNKTYPE, native.HOME_NAME + "/lib/regular")]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            probe = root / "symlink-capability-probe"
            fixture_symlink(self, probe, root / "missing")
            probe.unlink()
            result = self.extract(root, entries)
            self.assertEqual(3, result["members"])
            self.assertEqual(result["members"], result["logical_members"])
            self.assertEqual(3, result["headers"])
            self.assertEqual(native.MAX_RAW_HEADERS, result["max_raw_headers"])
            self.assertEqual(2, result["links"])
            self.assertEqual(len(b"synthetic bytes"), result["extracted_regular_bytes"])
            self.assertEqual(b"synthetic bytes", (root / "staging" / native.HOME_NAME / "lib/symbolic").read_bytes())

    @unittest.skipUnless(os.name == "posix", "POSIX mode bits only; native Windows executable/ACL semantics are not asserted")
    def test_actual_posix_archive_file_modes_strip_special_bits(self):
        body = b""
        for name, mode in (("executable", 0o6755), ("nonexecutable", 0o6644)):
            info = tarfile.TarInfo(native.HOME_NAME + "/" + name)
            info.mode, info.size = mode, 1
            body += synthetic_raw_tar_member(info, b"x")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            archive = root / "synthetic.tar.gz"
            archive.write_bytes(gzip.compress(body + b"\0" * 1024))
            native.extract_archive(archive, root / "staging")
            for name, mode in (("executable", 0o755), ("nonexecutable", 0o644)):
                self.assertEqual(mode, (root / "staging" / native.HOME_NAME / name).stat().st_mode & 0o7777)

    def test_symlink_fixture_skips_only_the_known_native_windows_privilege_gap(self):
        privilege = OSError("synthetic symlink privilege gap")
        privilege.winerror = 1314
        with patch.object(sys.modules[__name__], "os", SimpleNamespace(name="nt")):
            link = SimpleNamespace(symlink_to=Mock(side_effect=privilege))
            with self.assertRaises(unittest.SkipTest) as raised:
                fixture_symlink(self, link, "synthetic target")
        self.assertIn("ERROR_PRIVILEGE_NOT_HELD", str(raised.exception))
        for host, error in (("posix", privilege), ("nt", FileExistsError("synthetic collision")),
                            ("nt", OSError("synthetic unrelated fixture error"))):
            with self.subTest(host=host, error=type(error).__name__), \
                    patch.object(sys.modules[__name__], "os", SimpleNamespace(name=host)):
                link = SimpleNamespace(symlink_to=Mock(side_effect=error))
                with self.assertRaises(type(error)) as raised:
                    fixture_symlink(self, link, "synthetic target")
                self.assertIs(error, raised.exception)

    def test_traversal_absolute_wrong_root_special_duplicate_and_case_collision_rejected(self):
        cases = {
            "parent": [(native.HOME_NAME + "/../PRIVATE", tarfile.REGTYPE, b"x")],
            "absolute": [("/PRIVATE", tarfile.REGTYPE, b"x")],
            "wrong_root": [("other-distribution/bin/konanc", tarfile.REGTYPE, b"x")],
            "backslash": [(native.HOME_NAME + "/bin\\PRIVATE", tarfile.REGTYPE, b"x")],
            "drive": [(native.HOME_NAME + "/C:PRIVATE", tarfile.REGTYPE, b"x")],
            "fifo": [(native.HOME_NAME + "/fifo", tarfile.FIFOTYPE, "")],
            "duplicate": [(native.HOME_NAME + "/a", tarfile.REGTYPE, b"x")] * 2,
            "case_collision": [(native.HOME_NAME + "/a", tarfile.REGTYPE, b"x"), (native.HOME_NAME + "/A", tarfile.REGTYPE, b"x")],
        }
        for case, entries in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve(strict=True)
                with self.assertRaises(native.NativePrerequisiteError) as error:
                    self.extract(root, entries)
                self.assertNotIn("PRIVATE", str(error.exception))
                self.assertFalse((root / "data").exists())

    def test_escaping_missing_directory_cycle_and_write_through_links_rejected(self):
        prefix = native.HOME_NAME + "/"
        cases = {
            "escape": [(prefix + "lib/link", tarfile.SYMTYPE, "../../../PRIVATE")],
            "absolute": [(prefix + "lib/link", tarfile.SYMTYPE, "/PRIVATE")],
            "missing": [(prefix + "lib/link", tarfile.SYMTYPE, "missing")],
            "directory": [(prefix + "directory", tarfile.DIRTYPE, ""), (prefix + "link", tarfile.SYMTYPE, "directory")],
            "cycle": [(prefix + "a", tarfile.SYMTYPE, "b"), (prefix + "b", tarfile.SYMTYPE, "a")],
            "write_through": [(prefix + "regular", tarfile.REGTYPE, b"x"), (prefix + "link", tarfile.SYMTYPE, "regular"),
                              (prefix + "link/child", tarfile.REGTYPE, b"x")],
        }
        for case, entries in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve(strict=True)
                with self.assertRaises(native.NativePrerequisiteError) as error:
                    self.extract(root, entries)
                self.assertNotIn("PRIVATE", str(error.exception))
                self.assertFalse((root / "data").exists())

    def test_entry_total_bytes_individual_bytes_and_gzip_corruption_are_bounded(self):
        entries = [(native.HOME_NAME + "/" + name, tarfile.REGTYPE, b"xxxx") for name in ("a", "b", "c")]
        for constant, limit in (("MAX_MEMBERS", 2), ("MAX_EXTRACTED_BYTES", 5), ("MAX_FILE_BYTES", 3)):
            with self.subTest(constant=constant), tempfile.TemporaryDirectory() as temporary, patch.object(native, constant, limit):
                with self.assertRaises(native.NativePrerequisiteError):
                    self.extract(Path(temporary).resolve(strict=True), entries)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            archive = root / "synthetic.tar.gz"
            archive.write_bytes(b"PRIVATE invalid compressed stream")
            with self.assertRaises(native.NativePrerequisiteError) as error:
                native.extract_archive(archive, root / "staging")
            self.assertNotIn("PRIVATE", str(error.exception))

    def test_metadata_extension_is_bounded_before_tar_parser_reads_payload(self):
        for kind in native.METADATA_TYPES:
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve(strict=True)
                info = tarfile.TarInfo("synthetic-extension")
                info.type, info.size = kind, 65537
                archive = root / "synthetic.tar.gz"
                # Header only: bounded rejection must happen before any
                # extension parser attempts to consume the absent payload.
                archive.write_bytes(gzip.compress(synthetic_raw_tar_member(info)))
                with patch.object(tarfile.TarInfo, "_proc_pax", side_effect=AssertionError("unbounded PAX parser entered")) as pax, \
                        patch.object(tarfile.TarInfo, "_proc_gnulong", side_effect=AssertionError("unbounded GNU parser entered")) as gnu, \
                        self.assertRaises(native.NativePrerequisiteError) as error:
                    native.extract_archive(archive, root / "staging")
                self.assertEqual("native_archive_metadata_bound_exceeded", error.exception.code)
                pax.assert_not_called()
                gnu.assert_not_called()

    def test_actual_gnu_and_pax_sparse_parsers_are_rejected_before_internal_work(self):
        sparse = tarfile.TarInfo(native.HOME_NAME + "/synthetic-sparse")
        sparse.type = tarfile.GNUTYPE_SPARSE
        cases = {"gnu": gzip.compress(synthetic_raw_tar_member(sparse, format=tarfile.GNU_FORMAT)),
                 "pax_00": synthetic_pax_archive({"GNU.sparse.size": "1", "GNU.sparse.offset": "0", "GNU.sparse.numbytes": "1"}),
                 "pax_01": synthetic_pax_archive({"GNU.sparse.map": "0,1", "GNU.sparse.realsize": "1"}),
                 "pax_10": synthetic_pax_archive({"GNU.sparse.major": "1", "GNU.sparse.minor": "0"}, b"1000000000\n"),
                 "pax_name_only": synthetic_pax_archive({"GNU.sparse.name": "synthetic-sparse"}),
                 "pax_schily": synthetic_pax_archive({"SCHILY.filetype": "sparse"})}
        for case, body in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve(strict=True)
                archive = root / "synthetic.tar.gz"
                archive.write_bytes(body)
                with patch.object(tarfile.TarInfo, "_proc_sparse", side_effect=AssertionError("GNU sparse parser entered")) as gnu, \
                        patch.object(tarfile.TarInfo, "_proc_gnusparse_00", side_effect=AssertionError("PAX sparse 00 parser entered")) as pax00, \
                        patch.object(tarfile.TarInfo, "_proc_gnusparse_01", side_effect=AssertionError("PAX sparse 01 parser entered")) as pax01, \
                        patch.object(tarfile.TarInfo, "_proc_gnusparse_10", side_effect=AssertionError("PAX sparse 10 parser entered")) as pax10, \
                        self.assertRaises(native.NativePrerequisiteError) as error:
                    native.extract_archive(archive, root / "staging")
                self.assertEqual("native_archive_sparse_unsupported", error.exception.code)
                for parser in (gnu, pax00, pax01, pax10):
                    parser.assert_not_called()

    def test_effective_pax_size_is_bounded_after_override_before_file_read(self):
        for size in (-1, native.MAX_FILE_BYTES + 1):
            with self.subTest(size=size), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve(strict=True)
                archive = root / "synthetic.tar.gz"
                archive.write_bytes(synthetic_pax_archive({"size": str(size)}))
                with self.assertRaises(native.NativePrerequisiteError) as error:
                    native.extract_archive(archive, root / "staging")
                self.assertEqual("native_archive_member_bound_exceeded", error.exception.code)
                self.assertFalse((root / "staging" / native.HOME_NAME / "synthetic-pax-file").exists())

    def test_real_recursive_gnu_and_pax_headers_metadata_and_depth_are_counted(self):
        name = (native.HOME_NAME + "/synthetic-regular\0").encode("ascii")
        gnu = tarfile.TarInfo("synthetic-longname")
        gnu.type, gnu.size = tarfile.GNUTYPE_LONGNAME, len(name)
        regular = tarfile.TarInfo(native.HOME_NAME + "/synthetic-regular")
        regular.size = 1
        recursive = gzip.compress(synthetic_raw_tar_member(gnu, name) * 3 + synthetic_raw_tar_member(regular, b"x") + b"\0" * 1024)
        cases = (("MAX_RAW_HEADERS", 2, recursive, "native_archive_header_bound_exceeded"),
                 ("MAX_EXTENSION_DEPTH", 2, recursive, "native_archive_extension_depth_exceeded"),
                 ("MAX_METADATA_BYTES", len(name) + 1, recursive, "native_archive_metadata_bound_exceeded"),
                 ("MAX_RAW_HEADERS", 1, synthetic_pax_archive({"comment": "synthetic metadata"}), "native_archive_header_bound_exceeded"))
        for constant, limit, body, code in cases:
            with self.subTest(constant=constant, body_kind="pax" if limit == 1 else "gnu"), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(native, constant, limit):
                root = Path(temporary).resolve(strict=True)
                archive = root / "synthetic.tar.gz"
                archive.write_bytes(body)
                with self.assertRaises(native.NativePrerequisiteError) as error:
                    native.extract_archive(archive, root / "staging")
                self.assertEqual(code, error.exception.code)

    def test_logical_entries_and_raw_gnu_pax_headers_have_separate_exact_limits(self):
        directory = tarfile.TarInfo(native.HOME_NAME)
        directory.type = tarfile.DIRTYPE
        regular = tarfile.TarInfo(native.HOME_NAME + "/synthetic-regular")
        regular.size = 1
        name = (regular.name + "\0").encode("ascii")
        gnu = tarfile.TarInfo("synthetic-longname")
        gnu.type, gnu.size = tarfile.GNUTYPE_LONGNAME, len(name)
        pax = tarfile.TarInfo(regular.name)
        pax.size, pax.pax_headers = 1, {"comment": "synthetic metadata"}
        bodies = {
            "gnu": synthetic_raw_tar_member(gnu, name) + synthetic_raw_tar_member(regular, b"x"),
            "pax": synthetic_raw_tar_member(pax, b"x", format=tarfile.PAX_FORMAT),
        }
        for kind, members in bodies.items():
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(native, "MAX_MEMBERS", 2), patch.object(native, "MAX_RAW_HEADERS", 3):
                root = Path(temporary).resolve(strict=True)
                archive = root / "synthetic.tar.gz"
                archive.write_bytes(gzip.compress(synthetic_raw_tar_member(directory) + members + b"\0" * 1024))
                result = native.extract_archive(archive, root / "staging")
                self.assertEqual(2, result["members"])
                self.assertEqual(result["members"], result["logical_members"])
                self.assertEqual(2, result["namespace_entries"])
                self.assertEqual(3, result["headers"])
                self.assertEqual(2, result["max_members"])
                self.assertEqual(3, result["max_raw_headers"])
                self.assertGreater(result["metadata_bytes"], 0)
                self.assertEqual(b"x", (root / "staging" / regular.name).read_bytes())

    def test_raw_header_overflow_precedes_first_and_later_metadata_payload_processing(self):
        directory = tarfile.TarInfo(native.HOME_NAME)
        directory.type = tarfile.DIRTYPE
        regular = tarfile.TarInfo(native.HOME_NAME + "/synthetic-regular")
        regular.size = 1
        prefix = synthetic_raw_tar_member(directory) + synthetic_raw_tar_member(regular, b"x")
        for kind in native.METADATA_TYPES:
            extension = tarfile.TarInfo("synthetic-extension")
            extension.type, extension.size = kind, 17
            for phase, preceding, limit in (("first", b"", 0), ("later", prefix, 2)):
                with self.subTest(kind=kind, phase=phase), patch.object(native, "MAX_RAW_HEADERS", limit), \
                        patch.object(tarfile.TarInfo, "_proc_pax", side_effect=AssertionError("PAX payload parser entered")) as pax, \
                        patch.object(tarfile.TarInfo, "_proc_gnulong", side_effect=AssertionError("GNU payload parser entered")) as gnu:
                    budget = native.ExtractionBudget()
                    body = gzip.compress(preceding + synthetic_raw_tar_member(extension))
                    with self.assertRaises(native.NativePrerequisiteError) as error:
                        self.inspect_headers(body, budget)
                    self.assertEqual("native_archive_header_bound_exceeded", error.exception.code)
                    self.assertEqual(limit + 1, budget.headers)
                    self.assertEqual(limit, budget.logical_members)
                    self.assertEqual(0, budget.metadata_bytes)
                    self.assertEqual(0, budget.extension_depth)
                    pax.assert_not_called()
                    gnu.assert_not_called()

    def test_logical_member_overflow_precedes_first_and_later_file_payload_processing(self):
        directory = tarfile.TarInfo(native.HOME_NAME)
        directory.type = tarfile.DIRTYPE
        regular = tarfile.TarInfo(native.HOME_NAME + "/synthetic-regular")
        regular.size = 1
        first = synthetic_raw_tar_member(regular)
        later = synthetic_raw_tar_member(directory) + synthetic_raw_tar_member(regular)
        original = tarfile.TarInfo._proc_builtin
        for phase, raw, limit in (("first", first, 0), ("later", later, 1)):
            processed = []

            def observe_builtin(member, archive):
                processed.append(member.name)
                return original(member, archive)

            with self.subTest(phase=phase), patch.object(native, "MAX_MEMBERS", limit), \
                    patch.object(native, "MAX_RAW_HEADERS", 10), \
                    patch.object(tarfile.TarInfo, "_proc_builtin", new=observe_builtin):
                budget = native.ExtractionBudget()
                with self.assertRaises(native.NativePrerequisiteError) as error:
                    self.inspect_headers(gzip.compress(raw), budget)
                self.assertEqual("native_archive_member_bound_exceeded", error.exception.code)
                self.assertEqual(limit + 1, budget.headers)
                self.assertEqual(limit + 1, budget.logical_members)
                self.assertEqual([] if limit == 0 else [native.HOME_NAME], processed)

    def test_metadata_only_zero_and_tiny_payload_chains_have_raw_and_depth_bounds(self):
        for kind, payloads in ((tarfile.GNUTYPE_LONGNAME, (b"", b"file\0")),
                               (tarfile.XHDTYPE, (b"", b"13 comment=x\n"))):
            for payload in payloads:
                extension = tarfile.TarInfo("synthetic-extension")
                extension.type, extension.size = kind, len(payload)
                body = gzip.compress(synthetic_raw_tar_member(extension, payload) * 4 + b"\0" * 1024)
                for bound in ("raw", "depth"):
                    with self.subTest(kind=kind, payload_bytes=len(payload), bound=bound), \
                            patch.object(native, "MAX_RAW_HEADERS", 2 if bound == "raw" else 100), \
                            patch.object(native, "MAX_EXTENSION_DEPTH", 2 if bound == "depth" else 16):
                        budget = native.ExtractionBudget()
                        with self.assertRaises(native.NativePrerequisiteError) as error:
                            self.inspect_headers(body, budget)
                        self.assertEqual("native_archive_header_bound_exceeded" if bound == "raw" else
                                         "native_archive_extension_depth_exceeded", error.exception.code)
                        self.assertEqual(3, budget.headers)
                        self.assertEqual(0, budget.logical_members)
                        self.assertEqual(0, budget.extension_depth)
                        self.assertEqual(len(payload) * (2 if bound == "raw" else 3), budget.metadata_bytes)

    def test_maximum_mixed_extension_chain_yields_one_logical_member(self):
        directory = tarfile.TarInfo(native.HOME_NAME)
        directory.type = tarfile.DIRTYPE
        regular = tarfile.TarInfo(native.HOME_NAME + "/synthetic-regular")
        regular.size = 1
        name, comment = (regular.name + "\0").encode("ascii"), b"13 comment=x\n"
        gnu, pax = tarfile.TarInfo("synthetic-longname"), tarfile.TarInfo("synthetic-pax")
        gnu.type, gnu.size = tarfile.GNUTYPE_LONGNAME, len(name)
        pax.type, pax.size = tarfile.XHDTYPE, len(comment)
        extensions = (synthetic_raw_tar_member(gnu, name) + synthetic_raw_tar_member(pax, comment)) * 8
        body = gzip.compress(synthetic_raw_tar_member(directory) + extensions +
                             synthetic_raw_tar_member(regular, b"x") + b"\0" * 1024)
        with patch.object(native, "MAX_MEMBERS", 2), patch.object(native, "MAX_RAW_HEADERS", 18):
            members, budget = self.inspect_headers(body)
        self.assertEqual([native.HOME_NAME, regular.name], [member.name for member in members])
        self.assertEqual(2, budget.logical_members)
        self.assertEqual(18, budget.headers)
        self.assertEqual(8 * (len(name) + len(comment)), budget.metadata_bytes)
        self.assertEqual(0, budget.extension_depth)
        for bound in ("raw", "depth"):
            with self.subTest(bound=bound), patch.object(native, "MAX_RAW_HEADERS", 17 if bound == "raw" else 100), \
                    patch.object(native, "MAX_EXTENSION_DEPTH", 15 if bound == "depth" else 16):
                budget = native.ExtractionBudget()
                with self.assertRaises(native.NativePrerequisiteError) as error:
                    self.inspect_headers(body, budget)
                self.assertEqual("native_archive_header_bound_exceeded" if bound == "raw" else
                                 "native_archive_extension_depth_exceeded", error.exception.code)
                self.assertEqual(18 if bound == "raw" else 17, budget.headers)
                self.assertEqual(1, budget.logical_members)
                self.assertEqual(0, budget.extension_depth)

    def test_compressed_decompressed_read_and_cooperative_deadline_bounds_are_operative(self):
        body = synthetic_archive([(native.HOME_NAME + "/compressible", tarfile.REGTYPE, b"\0" * 131072)])
        for constant, limit, code in (("MAX_TAR_STREAM_BYTES", 1024, "native_archive_stream_byte_bound_exceeded"),
                                       ("MAX_ARCHIVE_BYTES", 16, "native_archive_compressed_byte_bound_exceeded")):
            with self.subTest(constant=constant), tempfile.TemporaryDirectory() as temporary, patch.object(native, constant, limit):
                root = Path(temporary).resolve(strict=True)
                archive = root / "synthetic.tar.gz"
                archive.write_bytes(body)
                with self.assertRaises(native.NativePrerequisiteError) as error:
                    native.extract_archive(archive, root / "staging")
                self.assertEqual(code, error.exception.code)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            archive = root / "synthetic.tar.gz"
            archive.write_bytes(body)
            with patch.object(native.time, "monotonic", side_effect=[0, 301]), self.assertRaises(native.NativePrerequisiteError) as error:
                native.extract_archive(archive, root / "staging")
            self.assertEqual("native_archive_extraction_timeout", error.exception.code)
        budget = native.ExtractionBudget()
        reader = native.BoundedReader(io.BytesIO(b"synthetic"), budget, compressed=False)
        for size in (-1, native.MAX_READ_BYTES + 1):
            with self.subTest(size=size), self.assertRaises(native.NativePrerequisiteError) as error:
                reader.read(size)
            self.assertEqual("native_archive_parser_read_bound_exceeded", error.exception.code)

    def test_implicit_parent_casefold_aliases_in_both_orders_and_namespace_depth_fail(self):
        entries = [(native.HOME_NAME + "/Foo/a", tarfile.REGTYPE, b"x"),
                   (native.HOME_NAME + "/foo/b", tarfile.REGTYPE, b"x")]
        for ordered in (entries, list(reversed(entries))):
            with self.subTest(first=ordered[0][0]), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaises(native.NativePrerequisiteError) as error:
                    self.extract(Path(temporary).resolve(strict=True), ordered)
                self.assertEqual("native_archive_case_collision", error.exception.code)
        with tempfile.TemporaryDirectory() as temporary, patch.object(native, "MAX_MEMBERS", 2):
            with self.assertRaises(native.NativePrerequisiteError) as error:
                self.extract(Path(temporary).resolve(strict=True), entries[:1])
            self.assertEqual("native_archive_namespace_bound_exceeded", error.exception.code)
        with tempfile.TemporaryDirectory() as temporary, patch.object(native, "MAX_PATH_COMPONENTS", 2):
            with self.assertRaises(native.NativePrerequisiteError) as error:
                self.extract(Path(temporary).resolve(strict=True), entries[:1])
            self.assertEqual("native_archive_path_unsafe", error.exception.code)

    def test_size_published_sha1_and_enrolled_sha256_are_independent_checks(self):
        body = b"synthetic archive integrity control"
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary).resolve(strict=True) / "synthetic.tar.gz"
            path.write_bytes(body)
            for alteration, code in (("size", "native_archive_size_mismatch"), ("sha1", "native_archive_published_sha1_mismatch"),
                                     ("sha256", "native_archive_enrolled_sha256_mismatch"), ("valid", None)):
                with self.subTest(alteration=alteration), \
                        patch.object(native, "ARCHIVE_SIZE", len(body) + (alteration == "size")), \
                        patch.object(native, "ARCHIVE_SHA1", "0" * 40 if alteration == "sha1" else hashlib.sha1(body).hexdigest()), \
                        patch.object(native, "ARCHIVE_SHA256", "0" * 64 if alteration == "sha256" else hashlib.sha256(body).hexdigest()):
                    if code:
                        with self.assertRaises(native.NativePrerequisiteError) as error:
                            native.verify_archive(path)
                        self.assertEqual(code, error.exception.code)
                    else:
                        native.verify_archive(path)


class NativeProvisioningMockTests(unittest.TestCase):
    @staticmethod
    def publication_argv(root):
        return ["provision_native.py", "--output", str(root / "build/ci-evidence/native.json"),
                "--selection", str(root / "build/ci-evidence/raw/native-provisioning/selection.json")]

    @staticmethod
    def synthetic_publication_result():
        # Publication-control shape only, not an enrolled toolchain claim.
        return {"state": "passed", "phase": "synthetic_publication_control", "errors": [],
                "tuple_qualified": False, "custom_rules_qualified": False, "release_qualified": False}

    def test_actual_cli_duplicate_invocation_preserves_existing_public_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            output = root / "build/ci-evidence/native.json"
            with patch.object(native, "REPO_ROOT", root), patch.object(sys, "argv", self.publication_argv(root)), \
                    patch.object(native, "provision", return_value=self.synthetic_publication_result()), redirect_stdout(io.StringIO()):
                self.assertEqual(0, native.main())
            before = output.read_bytes()
            with patch.object(native, "REPO_ROOT", root), patch.object(sys, "argv", self.publication_argv(root)), \
                    patch.object(native, "source_identity", return_value=IDENTITY), patch.object(native, "run_logged") as launcher, \
                    patch.object(native, "observed_prerequisites") as observed, redirect_stdout(io.StringIO()) as stream:
                self.assertEqual(1, native.main())
            self.assertEqual(before, output.read_bytes())
            self.assertIn("native_provisioning_output_not_fresh", stream.getvalue())
            launcher.assert_not_called()
            observed.assert_not_called()

    def publication_collision(self, collision):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            output = root / "build/ci-evidence/native.json"
            output.parent.mkdir(parents=True)
            sentinel = root / "private-sentinel"
            sentinel.write_bytes(b"PRIVATE retained collision bytes")
            old_temp = output.with_name(output.name + ".tmp")
            if collision == "fixed_file":
                old_temp.write_bytes(sentinel.read_bytes())
            elif collision == "fixed_symlink":
                fixture_symlink(self, old_temp, sentinel)

            def provision(*_):
                # Simulate a destination appearing after provision preflight
                # but before CLI finalization. No transport/probe is run.
                if collision == "destination_file":
                    output.write_bytes(sentinel.read_bytes())
                elif collision == "destination_symlink":
                    fixture_symlink(self, output, sentinel)
                return self.synthetic_publication_result()

            with patch.object(native, "REPO_ROOT", root), patch.object(sys, "argv", self.publication_argv(root)), \
                    patch.object(native, "provision", side_effect=provision), redirect_stdout(io.StringIO()) as stream:
                status = native.main()
            self.assertEqual(b"PRIVATE retained collision bytes", sentinel.read_bytes())
            if collision.startswith("fixed"):
                self.assertEqual(0, status)
                self.assertEqual(b"PRIVATE retained collision bytes", old_temp.read_bytes())
                self.assertEqual(self.synthetic_publication_result(), json.loads(output.read_bytes()))
            else:
                self.assertEqual(1, status)
                self.assertEqual(b"PRIVATE retained collision bytes", output.read_bytes())
            self.assertFalse(list(output.parent.glob(".native.json.*.pending")))
            self.assertNotIn("PRIVATE", stream.getvalue())

    def test_actual_cli_destination_collision_and_fixed_temp_files_never_clobber(self):
        for collision in ("fixed_file", "destination_file"):
            with self.subTest(collision=collision):
                self.publication_collision(collision)

    def test_actual_cli_fixed_temp_symlink_is_untouched(self):
        self.publication_collision("fixed_symlink")

    def test_actual_cli_racing_destination_symlink_never_clobbers_target(self):
        self.publication_collision("destination_symlink")

    def test_synthetic_racing_final_symlink_decision_denies_publication_without_host_link_privileges(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            output = root / "build/ci-evidence/native.json"
            sentinel = root / "private-sentinel"
            sentinel.write_bytes(b"PRIVATE retained synthetic alias target")
            raced = [False]
            real_is_symlink = Path.is_symlink
            real_publish = native.publish_json_no_clobber
            value = self.synthetic_publication_result()
            def publish(path, result):
                # The unmodified main/build_path guard has already accepted
                # this canonical path. Model the alias appearing ONLY at the
                # final no-clobber publication boundary, not before that guard.
                self.assertEqual(output, path)
                raced[0] = True
                return real_publish(path, result)
            def is_symlink(path):
                return (raced[0] and path == output) or real_is_symlink(path)
            with patch.object(native, "REPO_ROOT", root), patch.object(sys, "argv", self.publication_argv(root)), \
                    patch.object(native, "provision", return_value=value), \
                    patch.object(native, "publish_json_no_clobber", side_effect=publish) as publication, \
                    patch.object(Path, "is_symlink", autospec=True, side_effect=is_symlink), redirect_stdout(io.StringIO()) as stream:
                self.assertEqual(1, native.main())
            publication.assert_called_once_with(output, value)
            self.assertTrue(raced[0])
            self.assertEqual("native_provisioning_output_not_fresh\n", stream.getvalue())
            self.assertEqual(b"PRIVATE retained synthetic alias target", sentinel.read_bytes())
            self.assertFalse(output.exists())
            self.assertFalse(list(output.parent.glob(".native.json.*.pending")))

    def test_actual_cli_exclusive_temp_failure_and_final_link_collision_deny_publication(self):
        for phase in ("temporary", "link"):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve(strict=True)
                output = root / "build/ci-evidence/native.json"
                output.parent.mkdir(parents=True)
                sentinel = output.with_name(output.name + ".tmp")
                sentinel.write_bytes(b"PRIVATE retained scratch collision")
                target = native.tempfile if phase == "temporary" else native.os
                method = "mkstemp" if phase == "temporary" else "link"

                def collide(*args, **kwargs):
                    if phase == "link":
                        output.write_bytes(b"PRIVATE retained final destination")
                    raise FileExistsError("PRIVATE collision details")

                with patch.object(native, "REPO_ROOT", root), patch.object(sys, "argv", self.publication_argv(root)), \
                        patch.object(native, "provision", return_value=self.synthetic_publication_result()), \
                        patch.object(target, method, side_effect=collide), redirect_stdout(io.StringIO()) as stream:
                    self.assertEqual(1, native.main())
                self.assertEqual(b"PRIVATE retained scratch collision", sentinel.read_bytes())
                expected = "native_evidence_publication_failed" if phase == "temporary" else "native_provisioning_output_not_fresh"
                self.assertIn(expected, stream.getvalue())
                if phase == "link":
                    self.assertEqual(b"PRIVATE retained final destination", output.read_bytes())
                else:
                    self.assertFalse(output.exists())
                self.assertFalse(list(output.parent.glob(".native.json.*.pending")))
                self.assertNotIn("PRIVATE", stream.getvalue())

    def test_quiescent_host_and_unobserved_archive_layout_limitations_are_explicit(self):
        text = " ".join(native.LIMITATIONS)
        for required in ("fresh/quiescent", "same-UID", "not race-proof", "host attestation",
                         "Original author enrollment acquired and hashed", "bounded read-only inventory",
                         "header 100,001", "50,027 logical entries", "did not complete",
                         "source/synthetic-controlled only", "layout", "unvalidated", "cooperative", "not a preemptive"):
            self.assertIn(required, text)

    def run_provisioning(self, root, *, transport_state="completed", transport_exit=0, version_state="completed", version_exit=0,
                         version_text="info: kotlinc-native 2.4.21 (JRE 25.0.5)", input_change=False):
        argument, java_home, data_dir, _ = setup_attempt(root)
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
            archive.add(data_dir / native.HOME_NAME, arcname=native.HOME_NAME)
        body = buffer.getvalue()
        shutil.rmtree(argument.native_selection.parent)
        argument.native_provisioning.unlink()
        args = argparse.Namespace(output=argument.native_provisioning, selection=argument.native_selection,
                                  environment=argument.environment, targets=list(native.TARGETS))
        calls = []

        def launch(argv, **kwargs):
            calls.append(argv)
            if argv[0] == "/usr/bin/curl":
                self.assertEqual("--disable", argv[1])
                self.assertEqual(native.ARCHIVE_URL, argv[-1])
                self.assertIn("=https", argv)
                self.assertEqual("", argv[argv.index("--proxy") + 1])
                self.assertEqual("0", argv[argv.index("--max-redirs") + 1])
                self.assertEqual("0", argv[argv.index("--retry") + 1])
                self.assertEqual(600, kwargs["timeout"])
                self.assertEqual({"PATH", "LANG", "LC_ALL"}, set(kwargs["env"]))
                Path(argv[argv.index("--output") + 1]).write_bytes(body)
                state, code, text = transport_state, transport_exit, "200"
            else:
                self.assertEqual([str(data_dir / native.HOME_NAME / "bin/konanc"), "-version",
                                  "-Xoverride-konan-properties=airplaneMode=true"], argv)
                self.assertEqual(60, kwargs["timeout"])
                self.assertEqual(str(java_home), kwargs["env"]["JAVA_HOME"])
                self.assertEqual(str(data_dir), kwargs["env"]["KONAN_DATA_DIR"])
                self.assertEqual("0", kwargs["env"]["KONAN_USE_INTERNAL_SERVER"])
                self.assertEqual("-Xmx512m", kwargs["env"]["JAVA_OPTS"])
                self.assertEqual(DEVELOPER, kwargs["env"]["DEVELOPER_DIR"])
                self.assertNotIn("BASH_ENV", kwargs["env"])
                if input_change:
                    (java_home / "lib/modules").write_bytes(b"PRIVATE mutation during synthetic version probe")
                state, code, text = version_state, version_exit, version_text
            return {"state": state, "return_code": code, "command": ["PRIVATE raw command"], "reason": "PRIVATE raw process error",
                    "stdout_digest": "sha256:" + "3" * 64, "stderr_digest": "sha256:" + "4" * 64,
                    "elapsed_seconds": 0.1, "output_bytes": 64, "raw_logs_uploaded": False}, text, "PRIVATE tool stream"

        with patch.object(native, "source_identity", return_value=IDENTITY), \
                patch.object(native.platform, "system", return_value="Darwin"), \
                patch.object(native.platform, "machine", return_value="arm64"), \
                patch.dict(os.environ, dict(hosted_environment(java_home, data_dir), BASH_ENV="PRIVATE ambient shell injection"), clear=True), \
                patch.object(native, "ARCHIVE_SIZE", len(body)), patch.object(native, "ARCHIVE_SHA1", hashlib.sha1(body).hexdigest()), \
                patch.object(native, "ARCHIVE_SHA256", hashlib.sha256(body).hexdigest()), patch.object(native, "run_logged", side_effect=launch):
            result = native.provision(args, root)
            if result["state"] == "passed":
                native.write_json(args.output, result)
                _, _, selected_xcode = native.observed_prerequisites(argument.environment, root, IDENTITY)
                home, selected_data, reference = native.consume_selection(args.selection, args.output, "iosSimulatorArm64", root,
                                                                          IDENTITY, java_home, selected_xcode)
                self.assertEqual(data_dir / native.HOME_NAME, home)
                self.assertEqual(data_dir, selected_data)
                self.assertEqual("passed", reference["state"])
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertNotIn(str(root), json.dumps(result))
        return result, calls, argument

    def test_only_mocked_acquisition_and_version_probe_enroll_no_target_or_rule_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            result, calls, argument = self.run_provisioning(Path(temporary).resolve(strict=True))
            self.assertEqual("passed", result["state"])
            self.assertEqual(2, len(calls))
            self.assertTrue(argument.native_selection.is_file())
            self.assertTrue(result["archive_verified"])
            self.assertTrue(result["compiler_version_probe_executed"])
            for key in ("backend_bootstrap_executed", "target_compilation_executed", "target_runtime_executed", "tuple_qualified",
                        "custom_rules_qualified", "release_qualified", "raw_logs_uploaded"):
                self.assertIs(False, result[key])

    def test_failed_transport_retains_real_mock_attempt_and_never_probes_or_publishes_selection(self):
        for state, exit_code, reason in (("unavailable", None, "native_archive_transport_start_failed"),
                                         ("timeout", -15, "native_archive_transport_timeout"),
                                         ("completed", 22, "native_archive_transport_failed")):
            with self.subTest(state=state), tempfile.TemporaryDirectory() as temporary:
                result, calls, argument = self.run_provisioning(Path(temporary).resolve(strict=True), transport_state=state, transport_exit=exit_code)
                self.assertEqual("failed", result["state"])
                self.assertEqual([reason], result["errors"])
                self.assertEqual(1, len(calls))
                self.assertFalse(argument.native_selection.exists())
                self.assertFalse(result["compiler_version_probe_executed"])

    def test_failed_start_timeout_nonzero_version_and_changed_inputs_never_publish_selection(self):
        cases = (("unavailable", None, "kotlinc-native 2.4.21", False, "native_compiler_version_probe_start_failed"),
                 ("timeout", -15, "kotlinc-native 2.4.21", False, "native_compiler_version_probe_timeout"),
                 ("completed", 1, "PRIVATE error", False, "native_compiler_version_probe_failed"),
                 ("completed", 0, "kotlinc-native 2.4.20", False, "native_compiler_version_probe_mismatch"),
                 ("completed", 0, "kotlinc-native 2.4.21", True, "native_selected_inputs_changed_during_probe"))
        for state, exit_code, text, changed, reason in cases:
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as temporary:
                result, calls, argument = self.run_provisioning(Path(temporary).resolve(strict=True), version_state=state, version_exit=exit_code,
                                                              version_text=text, input_change=changed)
                self.assertEqual("failed", result["state"])
                self.assertEqual([reason], result["errors"])
                self.assertEqual(2, len(calls))
                self.assertFalse(argument.native_selection.exists())

    def test_nonfresh_output_stops_before_any_transport_or_version_probe(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            argument, _, _, _ = setup_attempt(root)
            args = argparse.Namespace(output=argument.native_provisioning, selection=argument.native_selection,
                                      environment=argument.environment, targets=list(native.TARGETS))
            with patch.object(native, "source_identity", return_value=IDENTITY), patch.object(native, "run_logged") as launcher:
                result = native.provision(args, root)
            launcher.assert_not_called()
            self.assertEqual(["native_provisioning_output_not_fresh"], result["errors"])
            self.assertEqual("unavailable", result["state"])

    def test_process_projection_drops_raw_paths_commands_reasons_and_unknown_states(self):
        value = native.process_projection({"state": "PRIVATE process", "return_code": True, "reason": "PRIVATE stderr",
                                           "command": ["PRIVATE source"], "stdout_digest": "PRIVATE path", "elapsed_seconds": float("nan")})
        self.assertEqual({"state": "invalid", "return_code": None, "raw_logs_uploaded": False}, value)


class NativeExplicitConsumerTests(unittest.TestCase):
    def test_platform_and_provider_use_the_same_home_and_cannot_inherit_ambient_discovery(self):
        for kind in ("platform", "provider"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve(strict=True)
                argument, java_home, data_dir, _ = setup_attempt(root)
                fixture = root / "fixtures/kmp"
                fixture.mkdir(parents=True)
                (fixture / "settings.gradle.kts").write_bytes(b"synthetic fixture; never execute")
                args = argparse.Namespace(profile="ios", output=root / "build/ci-evidence/" / (kind + ".json"), timeout=1,
                                          fixture=fixture, environment=argument.environment, native_selection=argument.native_selection,
                                          native_provisioning=argument.native_provisioning)
                wrapper = platform_checks if kind == "platform" else provider_checks
                expected = f"-Pkotlin.native.home={data_dir / native.HOME_NAME}" if kind == "platform" else \
                           f"-PplatformNativeHome={data_dir / native.HOME_NAME}"
                observed = []

                def command(arguments, **_):
                    observed.append(arguments)
                    self.assertIn(expected, arguments)
                    return {"state": "failed", "return_code": 1, "errors": ["synthetic expected failure"], "tasks": {}}, ""

                with patch.object(wrapper, "REPO_ROOT", root), patch.object(wrapper, "source_identity", return_value=IDENTITY), \
                        patch.object(wrapper, "preflight", return_value={"state": "passed", "errors": [], "simulator": {"udid": "synthetic"}}), \
                        patch.object(native.platform, "system", return_value="Darwin"), patch.object(native.platform, "machine", return_value="arm64"), \
                        patch.dict(os.environ, hosted_environment(java_home, data_dir), clear=True), patch.object(native, "verify_archive"), \
                        patch.object(native, "run_logged") as launcher, patch.object(wrapper, "gradle_result", side_effect=command):
                    result = wrapper.execute(args)
                self.assertTrue(observed)
                self.assertEqual("failed", result["state"])
                self.assertEqual("passed", result["prerequisites"]["native_selection"]["state"])
                launcher.assert_not_called()

    def test_workflow_enrollment_and_fir_are_independent_and_upload_no_private_selection(self):
        workflow = (checks.REPO_ROOT / ".github/workflows/platform-validation.yml").read_text()
        ios = workflow.split("\n  ios-native:\n", 1)[1]
        wrapper = ios.split("- name: Execute pinned wrapper version", 1)[1].split("- name:", 1)[0]
        self.assertIn("if: ${{ !cancelled() }}", wrapper)
        self.assertIn("-- --version", wrapper)
        provisioning = workflow.split("- name: Enroll exact checksum-pinned Native", 1)[1].split("- name:", 1)[0]
        semantic = workflow.split("- name: Execute independent offline Native FIR", 1)[1].split("- name:", 1)[0]
        for section in (provisioning, semantic):
            self.assertIn("if: ${{ !cancelled() }}", section)
            self.assertIn("build/ci-evidence/raw/native-provisioning/selection.json", section)
            self.assertIn("build/ci-evidence/native.json", section)
        self.assertIn("--command-result build/ci-evidence/native.json", workflow)
        self.assertIn("path: build/ci-evidence/*.json", workflow)
        self.assertNotIn("path: build/ci-evidence/raw", workflow)
        self.assertNotIn("run_native_throws_checks", workflow)
        semantic_source = (checks.REPO_ROOT / "scripts/ci/run_semantic_checks.py").read_text()
        self.assertNotIn("Path.home()", semantic_source)
        self.assertNotIn("acquire_archive(", semantic_source)
        self.assertNotIn("native_home(data_dir", semantic_source)


if __name__ == "__main__":
    unittest.main()

"""Synthetic Native wrapper/metadata controls; never execute a JVM or Native compiler."""

import argparse
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

try:
    from . import run_semantic_checks as checks
except ImportError:
    import run_semantic_checks as checks


IDENTITY = {"commit": "1" * 40, "dirty": False, "working_tree_digest": "sha256:" + "2" * 64}
RETAINED_JVM = checks.REPO_ROOT / "modules/semantic-facts/evidence/2026-11-04T091649Z-jvm/summary.json"


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
    for relative in ("bin", "tools", "konan/lib", "klib/common", "klib/platform/ios_simulator_arm64"):
        (home / relative).mkdir(parents=True, exist_ok=True)
    (home / "bin/konanc").write_bytes(b"synthetic launcher; never execute")
    (home / "bin/konanc").chmod(0o755)
    (home / "tools/env_blacklist").write_bytes(b"synthetic env input")
    (home / "konan/konan.properties").write_text(
        "targetSysRoot.ios_simulator_arm64 = target-sysroot-xcode_26.4_17E192-iphonesimulator\n"
        "llvmHome.macos_arm64 = $llvm.macos_arm64.dev\nllvm.macos_arm64.dev = synthetic-llvm-19\n"
        "libffiDir.macos_arm64 = libffi-3.3-1-macos-arm64\n")
    with zipfile.ZipFile(home / "konan/lib/kotlin-native-compiler-embeddable.jar", "w") as jar:
        jar.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\r\nImplementation-Version: {version}\r\n\r\n")
    return home


def setup_attempt(root):
    argument = argparse.Namespace(output=root / "build/ci-evidence/ios-semantic.json",
                                  environment=root / "build/ci-evidence/environment.json", target="iosSimulatorArm64", timeout=1)
    java_home = root / "synthetic-jdk"
    for relative in ("bin/java", "release", "lib/modules"):
        path = java_home / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"synthetic JDK input; never execute")
    data_dir = root / "synthetic-konan"
    home = native_tree(data_dir)
    plugin = root / "modules/semantic-facts/build/libs/semantic-facts-0.1.0-dev.1-kotlin-2.4.21-plugin.jar"
    plugin.parent.mkdir(parents=True, exist_ok=True)
    plugin.write_bytes(b"synthetic plugin input; never execute")
    environment = {"state": "failed", "source": IDENTITY, "host": {"os": "Darwin", "arch": "arm64"},
                   "java": {"major": 25, "path": str(java_home / "bin/java"),
                            "launcher_digest": checks.digest_file(java_home / "bin/java"),
                            "release_file_digest": checks.digest_file(java_home / "release"),
                            "release_properties": {"JAVA_VERSION": "25.0.5", "JAVA_RUNTIME_VERSION": "25.0.5+9", "IMPLEMENTOR": "Synthetic fixture"}},
                   "xcode": {"state": "observed", "version": "26.4.1", "build": "17E202", "iphoneos_sdk": "26.4", "iphonesimulator_sdk": "26.4",
                             "available_simulator_runtimes": []}, "android": {"compile_sdk_37_installed": False}, "gradle": {"state": "pinned"}}
    argument.environment.parent.mkdir(parents=True, exist_ok=True)
    argument.environment.write_text(json.dumps(environment))
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
    def test_exact_existing_mac_arm64_package_and_manifest_version_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            data_dir = Path(temporary)
            home = native_tree(data_dir)
            self.assertEqual(home.resolve(), checks.native_home(data_dir, "iosSimulatorArm64"))
            native_tree(data_dir, "kotlin-native-prebuilt-macos-arm64-2.4.21")
            with self.assertRaises(ValueError):
                checks.native_home(data_dir, "iosSimulatorArm64")

    def test_wrong_arch_old_version_missing_target_and_duplicate_manifest_do_not_pass(self):
        for alteration in ("architecture", "version", "target", "manifest"):
            with tempfile.TemporaryDirectory() as temporary:
                data_dir = Path(temporary)
                home = native_tree(data_dir, "kotlin-native-prebuilt-macos-x86_64-2.4.21" if alteration == "architecture" else
                                   "kotlin-native-prebuilt-macos-aarch64-2.4.21", "2.4.20" if alteration == "version" else "2.4.21")
                if alteration == "target":
                    (home / "klib/platform/ios_simulator_arm64").rmdir()
                elif alteration == "manifest":
                    with zipfile.ZipFile(home / "konan/lib/kotlin-native-compiler-embeddable.jar", "w") as jar:
                        jar.writestr("META-INF/MANIFEST.MF", "Implementation-Version: 2.4.21\nImplementation-Version: 2.4.20\n")
                with self.subTest(alteration=alteration), self.assertRaises(ValueError):
                    checks.native_home(data_dir, "iosSimulatorArm64")

    def test_native_cached_prerequisites_do_not_equate_configured_sysroot_with_host_xcode(self):
        with tempfile.TemporaryDirectory() as temporary:
            data_dir = Path(temporary)
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
            data_dir = Path(temporary)
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
                patch.dict(os.environ, {"JAVA_HOME": str(java_home), "KONAN_DATA_DIR": str(data_dir)}), \
                patch.object(checks, "gradle_result", side_effect=command) as gradle:
            result = checks.run(argument, root)
        return result, gradle

    def test_successful_synthetic_wrapper_is_metadata_only_and_independent_of_android_runtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
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

    def test_failed_dependency_attempt_preserved_not_fabricated_empty_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
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
            root = Path(temporary)
            argument, java_home, data_dir, summary = setup_attempt(root)
            summary.update(state="unavailable", cases=[], executions=[], reasons=["semantic_native_distribution_incomplete"])
            result, _ = self.run_attempt(root, argument, java_home, data_dir, summary, "failed")
            self.assertEqual("unavailable", result["state"])
            self.assertEqual(0, result["compiler_execution_count"])
            self.assertTrue(result["gradle_attempt_executed"])

    def test_missing_summary_or_false_assertion_never_passes(self):
        for summary_present in (False, True):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                argument, java_home, data_dir, summary = setup_attempt(root)
                summary["cases"][0]["assertions"]["native_is_not_jvm_substitution"] = False
                result, _ = self.run_attempt(root, argument, java_home, data_dir, summary, write_summary=summary_present)
                with self.subTest(summary_present=summary_present):
                    self.assertEqual("failed", result["state"])

    def test_unsafe_or_nonfresh_private_output_stops_before_any_gradle_attempt(self):
        for failure in ("outside_build", "traversal", "nonfresh"):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
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
            root = Path(temporary)
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
                    patch.dict(os.environ, {"JAVA_HOME": str(java_home), "KONAN_DATA_DIR": str(data_dir)}), patch.object(checks, "gradle_result") as gradle:
                result = checks.run(argument, root)
            gradle.assert_not_called()
            self.assertEqual("unavailable", result["state"])


if __name__ == "__main__":
    unittest.main()

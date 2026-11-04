"""SDK inventory/provisioner infrastructure controls; no SDK/JVM download or execution."""

import argparse
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

try:
    from . import ci_common, provision_android, record_environment
except ImportError:
    import ci_common
    import provision_android
    import record_environment


class AndroidSdkInfrastructureTests(unittest.TestCase):
    def installed(self, root: Path, *, platform="37.0", api="37.0", build_tools="36.0.0", extra=""):
        directory = root / "platforms" / ("android-" + platform)
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "android.jar").write_bytes(b"synthetic android.jar, not an actual Android platform")
        (directory / "source.properties").write_text(
            f"Pkg.Revision=2\nAndroidVersion.ApiLevel={api}\nAndroidVersion.CodeName=\n"
            "AndroidVersion.ExtensionLevel=22\nAndroidVersion.IsBaseSdk=true\nAndroidVersion.PreviewSdkInt=0\n" + extra,
            encoding="utf-8",
        )
        tools = root / "build-tools/36.0.0"
        tools.mkdir(parents=True, exist_ok=True)
        (tools / "source.properties").write_text(f"Pkg.Revision={build_tools}\n", encoding="utf-8")

    def test_package_identifier_is_stable_major_37_minor_zero_not_absent_old_name(self):
        self.assertEqual(("platforms;android-37.0", "build-tools;36.0.0"), provision_android.PACKAGES)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.installed(root, platform="37", api="37")
            before = provision_android.installed_packages(root)
            self.assertFalse(before[0]["installed"])
            self.installed(root)
            after = provision_android.installed_packages(root)
            self.assertTrue(all(row["installed"] for row in after))
            self.assertEqual({"raw": "37.0", "major": 37, "minor": 0}, after[0]["observed_android_api"])
            self.assertEqual({"major": 37, "minor": 0}, after[0]["expected_android_api"])

    def test_inventory_hashes_actual_metadata_and_primary_file_without_distribution_lock_claim(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.installed(root)
            rows = provision_android.installed_packages(root)
            platform = root / provision_android.PLATFORM_PATH
            self.assertEqual(ci_common.digest_file(platform / "android.jar"), rows[0]["primary_artifact_digest"])
            self.assertEqual(ci_common.digest_file(platform / "source.properties"), rows[0]["source_properties_digest"])
            self.assertTrue(all(row["whole_distribution_hash_locked"] is False for row in rows))
            self.assertNotIn("synthetic android.jar", json.dumps(rows))

    def test_wrong_api_minor_preview_base_sdk_and_package_path_fail_closed(self):
        variants = (("36", ""), ("37.1", ""), ("37.2", ""), ("37.0.0", ""), ("invalid", ""),
                    ("37.0", "AndroidVersion.CodeName=Preview\n"),
                    ("37.0", "AndroidVersion.IsBaseSdk=false\n"),
                    ("37.0", "AndroidVersion.PreviewSdkInt=1\n"),
                    ("37.0", "Pkg.Path=platforms;android-37.1\n"))
        for api, override in variants:
            with self.subTest(api=api, override=override), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.installed(root, api=api)
                properties = root / provision_android.PLATFORM_PATH / "source.properties"
                if override:
                    key = override.partition("=")[0]
                    text = properties.read_text(encoding="utf-8")
                    properties.write_text("\n".join(line for line in text.splitlines() if not line.startswith(key + "=")) + "\n" + override,
                                          encoding="utf-8")
                self.assertFalse(provision_android.installed_packages(root)[0]["installed"])

    def test_wrong_build_tools_or_missing_primary_file_is_not_installed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.installed(root, build_tools="36.0.0 rc5")
            self.assertFalse(provision_android.installed_packages(root)[1]["installed"])
            (root / provision_android.PLATFORM_PATH / "android.jar").unlink()
            self.assertFalse(provision_android.installed_packages(root)[0]["installed"])

    def test_properties_omit_unknown_fields_and_bound_selected_public_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "source.properties"
            path.write_text("TOKEN_SECRET=PRIVATE must not be recorded\n#Pkg.Revision=ignored\n"
                            "Pkg.Revision=2\nPkg.Desc=public\x00metadata" + "x" * 600 + "\n", encoding="utf-8")
            value = provision_android.properties_file(path)
            self.assertEqual("2", value["Pkg.Revision"])
            self.assertLessEqual(len(value["Pkg.Desc"]), 512)
            self.assertNotIn("\x00", value["Pkg.Desc"])
            self.assertNotIn("PRIVATE", json.dumps(value))
            self.assertNotIn("TOKEN_SECRET", value)

    def test_duplicate_invalid_utf8_or_oversized_properties_are_not_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "source.properties"
            for raw in (b"Pkg.Revision=2\nPkg.Revision=3\n", b"Pkg.Desc=\xff\n",
                        b"x" * (provision_android.MAX_PROPERTIES_BYTES + 1)):
                path.write_bytes(raw)
                with self.assertRaises(ValueError):
                    provision_android.properties_file(path)

    def test_failure_categories_preserve_only_known_codes_never_messages_or_license_text(self):
        output = "\n".join(("Warning: Failed to find package 'platforms;android-37.0'",
                            "License for package is not accepted. PRIVATE license or token text",
                            "Failed to download PRIVATE/repository.xml",
                            "NumberFormatException: PRIVATE metadata",
                            "UnsupportedClassVersionError: PRIVATE runtime",
                            "unknown argument: PRIVATE_ARGUMENT"))
        categories = provision_android.failure_categories(output)
        self.assertEqual(sorted(("selected_package_unavailable", "sdk_license_acceptance_unavailable",
                                 "sdk_repository_download_failed", "sdk_metadata_parse_failed",
                                 "sdk_manager_java_incompatible", "sdk_manager_argument_rejected")), categories)
        self.assertNotIn("PRIVATE", json.dumps(categories))
        self.assertEqual([], provision_android.failure_categories("arbitrary PRIVATE unclassified failure"))
        self.assertEqual(["sdk_license_acceptance_unavailable"], provision_android.failure_categories("Unaccepted licenses:"))

    def test_existing_correct_inventory_does_not_invoke_manager_or_accept_licenses(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.installed(root)
            with patch.dict(os.environ, {"ANDROID_HOME": str(root)}, clear=True), \
                 patch.object(provision_android, "run_logged") as run:
                result = provision_android.provision(root / "sdk.json")
            self.assertEqual("passed", result["state"])
            self.assertEqual(list(provision_android.PACKAGES), result["selected_packages"])
            self.assertNotIn("execution", result)
            run.assert_not_called()

    def test_absent_sdk_root_or_manager_retains_unavailable_state_without_tool_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for environment in ({}, {"ANDROID_SDK_ROOT": str(root)}):
                with self.subTest(environment=environment), patch.dict(os.environ, environment, clear=True), \
                     patch.object(provision_android.shutil, "which", return_value=None), \
                     patch.object(provision_android, "run_logged") as run:
                    result = provision_android.provision(root / "sdk.json")
                self.assertEqual("unavailable", result["state"])
                self.assertTrue(result["errors"])
                if environment:
                    self.assertEqual(2, len(result["packages"]))
                    self.assertFalse(any(row["installed"] for row in result["packages"]))
                run.assert_not_called()

    def test_real_manager_selection_uses_only_exact_packages_never_license_acceptance(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manager = root / "cmdline-tools/latest/bin/sdkmanager"
            manager.parent.mkdir(parents=True)
            manager.write_bytes(b"synthetic manager; not executable")
            (manager.parent.parent / "source.properties").write_text("Pkg.Revision=23.0\n", encoding="utf-8")

            def run(argv, **kwargs):
                self.assertEqual([str(manager), f"--sdk_root={root}", *provision_android.PACKAGES], argv)
                self.assertNotIn("--licenses", argv)
                self.assertEqual(300, kwargs["timeout"])
                self.installed(root)
                return {"state": "completed", "return_code": 0}, "", ""

            with patch.dict(os.environ, {"ANDROID_HOME": str(root)}, clear=True), \
                 patch.object(provision_android.shutil, "which", return_value=None), \
                 patch.object(provision_android, "run_logged", side_effect=run) as execution:
                result = provision_android.provision(root / "sdk.json")
            self.assertEqual("passed", result["state"])
            self.assertEqual("23.0", result["sdkmanager_properties"]["Pkg.Revision"])
            execution.assert_called_once()

    def test_command_failure_or_missing_post_install_files_cannot_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for state, code in (("completed", 1), ("timeout", -9), ("completed", 0)):
                with self.subTest(state=state, code=code), \
                     patch.dict(os.environ, {"ANDROID_HOME": str(root)}, clear=True), \
                     patch.object(provision_android.shutil, "which", return_value=str(root / "sdkmanager")), \
                     patch.object(provision_android, "run_logged", return_value=(
                         {"state": state, "return_code": code}, "PRIVATE license: not accepted", "")):
                    result = provision_android.provision(root / "sdk.json")
                self.assertEqual("failed", result["state"])
                self.assertNotIn("PRIVATE", json.dumps(result))

    def test_environment_uses_the_same_actual_platform_37_zero_inventory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.installed(root)
            args = argparse.Namespace(gradle_result=None, require_java_major=25, require_gradle=None,
                                      require_xcode=None, require_xcode_build=None)
            with patch.dict(os.environ, {"ANDROID_HOME": str(root), "JAVA_HOME": str(root / "jdk25")}, clear=True), \
                 patch.object(record_environment, "probe", return_value=(0, 'openjdk version "25.0.5"\n')), \
                 patch.object(record_environment, "wrapper_profile", return_value={"state": "pinned"}), \
                 patch.object(record_environment, "source_identity", return_value={}), \
                 patch.object(record_environment, "observe_xcode", return_value={"state": "unavailable"}):
                result = record_environment.record(args)
            self.assertEqual("passed", result["state"])
            self.assertTrue(result["android"]["compile_sdk_37_installed"])
            self.assertTrue(result["android"]["build_tools_36_0_0_installed"])
            self.assertEqual(list(provision_android.PACKAGES), [row["package"] for row in result["android"]["packages"]])


if __name__ == "__main__":
    unittest.main()

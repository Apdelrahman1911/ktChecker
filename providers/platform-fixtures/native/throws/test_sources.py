"""Source/manifest checks only. Run with python3 -B; never invokes Gradle/JVM/Native.

These checks do not prove diagnostic emission, Native ABI behavior or qualification.
"""
from __future__ import annotations

from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import stat
import unittest


FIXTURE = Path(__file__).resolve().parent
REPOSITORY = FIXTURE.parents[3]
MODULE = REPOSITORY / "modules/provider-native"
DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")
RULES = {
    "THROWS_LIST_EMPTY": "ktc.compiler.native.throws_list_empty",
    "INCOMPATIBLE_THROWS_OVERRIDE": "ktc.compiler.native.incompatible_throws_override",
    "INCOMPATIBLE_THROWS_INHERITED": "ktc.compiler.native.incompatible_throws_inherited",
    "MISSING_EXCEPTION_IN_THROWS_ON_SUSPEND": "ktc.compiler.native.missing_exception_in_throws_on_suspend",
}


def digest(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


class SourceFreezeTests(unittest.TestCase):
    def setUp(self):
        self.cohort = json.loads((FIXTURE / "cohort.json").read_text())
        self.manifest = json.loads((FIXTURE / "source-manifest.json").read_text())
        self.cases = {row["name"]: row for row in self.cohort["cases"]}
        self.model_source = (MODULE / "src/main/kotlin/dev/ktchecker/providers/native/NativeThrowsFixtureCases.kt").read_text()

    def test_source_manifest_digest_is_pinned_in_the_separate_driver_model(self):
        match = re.search(r'SOURCE_MANIFEST_DIGEST = "(sha256:[0-9a-f]{64})"', self.model_source)
        self.assertIsNotNone(match)
        self.assertEqual(digest(FIXTURE / "source-manifest.json"), match.group(1))

    def test_manifest_lists_every_authored_regular_file_without_generated_alternatives(self):
        paths = [path for path in FIXTURE.rglob("*") if not path.is_dir() and path.name != "source-manifest.json"]
        expected = {path.relative_to(FIXTURE).as_posix() for path in paths}
        declared = [row["path"] for row in self.manifest["files"]]
        self.assertEqual(len(declared), len(set(declared)))
        self.assertEqual(expected, set(declared))
        for path in paths:
            info = path.lstat()
            self.assertTrue(stat.S_ISREG(info.st_mode))
            self.assertFalse(path.is_symlink())
            self.assertEqual(1, info.st_nlink)

    def test_each_authored_input_has_exact_lf_utf8_bytes_size_and_sha256(self):
        for row in self.manifest["files"]:
            path = FIXTURE / row["path"]
            self.assertEqual(digest(path), row["digest"])
            self.assertEqual(path.stat().st_size, row["size"])
            self.assertRegex(row["digest"], DIGEST)
            data = path.read_bytes()
            data.decode("utf-8", errors="strict")
            self.assertNotIn(b"\r", data)

    def test_source_provenance_and_version_target_scope_remain_explicitly_unexecuted(self):
        for document in (self.cohort, self.manifest):
            self.assertEqual(1, document["schemaVersion"])
            self.assertEqual("native-throws-2.4.21", document["cohortId"])
            self.assertEqual("2.4.21", document["kotlinVersion"])
            self.assertEqual("authored_unexecuted", document["sourceProvenance"])
            self.assertEqual(["iosArm64", "iosSimulatorArm64"], document["targets"])

    def test_only_four_exact_pinned_native_error_factories_are_selected(self):
        rows = self.cohort["rules"]
        self.assertEqual(4, len(rows))
        self.assertEqual(RULES, {row["upstreamId"]: row["ruleId"] for row in rows})
        self.assertEqual([34, 35, 36, 37], [row["factoryLine"] for row in rows])
        for row in rows:
            self.assertEqual("FirNativeErrors", row["factory"])
            self.assertEqual("ERROR", row["declaredSeverity"])

    def test_twenty_controls_distinguish_errors_compatible_alternatives_and_observations(self):
        self.assertEqual(20, len(self.cases))
        self.assertEqual(20, len(self.cohort["cases"]))
        self.assertEqual(Counter({"named_error": 7, "compatible": 11, "observation_only": 2}),
                         Counter(row["expectation"] for row in self.cases.values()))
        for row in self.cases.values():
            self.assertEqual(RULES[row["upstreamId"]], row["ruleId"])
            self.assertEqual("cases/" + row["name"] + "/iosMain/Contract.kt", row["sourcePath"])

    def test_expected_anchors_are_real_source_ranges_not_executed_baselines(self):
        for row in self.cases.values():
            lines = (FIXTURE / row["sourcePath"]).read_text().splitlines()
            if row["expectation"] == "named_error":
                self.assertEqual(1, len(row["anchors"]))
            if row["expectation"] == "compatible":
                self.assertFalse(row["anchors"])
            for anchor in row["anchors"]:
                self.assertGreaterEqual(anchor["line"], 1)
                self.assertLessEqual(anchor["line"], len(lines))
                self.assertGreaterEqual(anchor["firstColumn"], 0)
                self.assertLessEqual(anchor["firstColumn"], anchor["lastColumn"])
                self.assertLessEqual(anchor["lastColumn"], len(lines[anchor["line"] - 1]))
                self.assertTrue("@Throws" in lines[anchor["line"] - 1] or "override fun" in lines[anchor["line"] - 1])

    def test_real_stdlib_sources_do_not_replay_ignored_synthetic_kotlin_packages(self):
        expected = {row["sourcePath"] for row in self.cases.values()} | {"src/commonMain/kotlin/CommonBoundary.kt"}
        actual = {path.relative_to(FIXTURE).as_posix() for path in FIXTURE.rglob("*.kt")}
        self.assertEqual(expected, actual)
        for path in actual:
            source = (FIXTURE / path).read_text()
            self.assertNotIn("package kotlin", source)
            self.assertNotIn("IGNORE_FIR_DIAGNOSTICS", source)
            self.assertNotIn("ALLOW_KOTLIN_PACKAGE", source)
            self.assertNotIn("<!", source)

    def test_native_only_build_declares_two_ios_targets_and_no_android_or_jvm_substitute(self):
        source = (FIXTURE / "build.gradle.kts").read_text()
        for required in ('version "2.4.21"', "iosArm64()", "iosSimulatorArm64()", "jvmToolchain(25)",
                         "applyDefaultHierarchyTemplate()", 'listOf("cases/$fixtureCase/iosMain")'):
            self.assertIn(required, source)
        for forbidden in ('id("com.android', 'jvm("', "binaries.framework", "KotlinNativeSimulatorTest"):
            self.assertNotIn(forbidden, source)
        allowed_block = source.split("require(fixtureCase in setOf(", 1)[1].split("))", 1)[0]
        self.assertEqual(set(self.cases), set(re.findall(r'"([a-z-]+)"', allowed_block)))

    def test_suspend_controls_use_real_cancellation_exact_alias_and_all_four_recognized_superclasses(self):
        exact = (FIXTURE / self.cases["suspend-cancellation-exact"]["sourcePath"]).read_text()
        self.assertIn("kotlin.coroutines.cancellation.CancellationException::class", exact)
        self.assertIn("CancellationAlias::class", exact)
        superclass = (FIXTURE / self.cases["suspend-cancellation-superclass"]["sourcePath"]).read_text()
        for name in ("IllegalStateException", "RuntimeException", "Exception", "Throwable"):
            self.assertIn("@Throws(" + name + "::class)", superclass)
        ordinary = (FIXTURE / self.cases["suspend-cancellation-nonsuspend"]["sourcePath"]).read_text()
        self.assertNotIn("suspend fun", ordinary)

    def test_suppression_keeps_an_adjacent_violation_and_demotion_is_observation_only(self):
        suppression = self.cases["override-filter-suppression"]
        source = (FIXTURE / suppression["sourcePath"]).read_text()
        self.assertIn('@Suppress("INCOMPATIBLE_THROWS_OVERRIDE")', source)
        self.assertIn("AdjacentBrokenReader", source)
        self.assertEqual(2, len(suppression["anchors"]))
        self.assertEqual("observation_only", suppression["expectation"])
        demotion = self.cases["override-filter-demotion-denied"]
        self.assertEqual("observation_only", demotion["expectation"])
        self.assertEqual(["-Xwarning-level=INCOMPATIBLE_THROWS_OVERRIDE:warning"], demotion["compilerArguments"])
        self.assertEqual(["-nowarn"], self.cases["override-filter-nowarn"]["compilerArguments"])
        self.assertEqual("planned_downstream_control_not_implemented_by_native_driver", self.cohort["ownerDisposition"]["state"])

    def test_upstream_witnesses_are_exact_tagged_read_only_sources_not_execution_claims(self):
        upstream = self.cohort["upstream"]
        self.assertEqual("sha256:6ee2c2026ab8fdde96e6f14b84262dfa3ac22ea7510e82b42489d846735b9202", upstream["archiveDigest"])
        self.assertEqual(6, len(upstream["sourceEvidence"]))
        for row in upstream["sourceEvidence"]:
            self.assertEqual("https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/" + row["path"], row["url"])
            self.assertRegex(row["digest"], DIGEST)
            self.assertGreater(row["size"], 0)
        self.assertIn("IGNORE_FIR_DIAGNOSTICS", upstream["testSeedHarness"])
        self.assertIn("not compiler or adapter execution", upstream["evidenceKind"])

    def test_new_opt_in_task_requires_all_seven_owner_arguments_without_extending_old_driver(self):
        source = (MODULE / "build.gradle.kts").read_text()
        task = source.split('tasks.register<JavaExec>("nativeThrowsFixture")', 1)[1]
        for property_name in ("platformThrowsFixture", "platformGradle", "platformTarget", "platformThrowsCase", "platformResult",
                              "platformNativeHome", "platformJavaHome"):
            self.assertIn('gradleProperty("' + property_name + '").get()', task)
        self.assertNotIn("orElse", task)
        self.assertNotIn("orNull", task)
        old = (MODULE / "src/main/kotlin/dev/ktchecker/providers/native/NativeFixtureMain.kt").read_text()
        self.assertIn('setOf("positive", "missing-actual", "wrong-actual")', old)
        self.assertNotIn("nativeThrows", old)


if __name__ == "__main__":
    unittest.main()

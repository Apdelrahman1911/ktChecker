"""Fixed B1 source-resource/cache wiring and synthetic clean-checkout projections only.

No Gradle/Kotlin/JAR class execution, downloads or real wrapper-consumer qualification.
The projection models only these exact reviewed CopySpec/source-set lines; root's
actual B1 JVM run separately checks Gradle's behavior and the resulting bytes.
"""

from pathlib import Path
import re
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
BUILD_PATH = "modules/provider-build-contracts/build.gradle.kts"
SOURCE_PATH = "gradle/wrapper/gradle-wrapper.jar"
RESOURCE_PATH = "fixtures/gradle-wrapper-7a9ce74c.jar"
RESOURCE_ROOT = "modules/provider-build-contracts/src/test/resources/"
PIN = "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"


class BuildContractsFixtureWiringTests(unittest.TestCase):
    def document(self):
        return (REPO_ROOT / BUILD_PATH).read_text(encoding="utf-8")

    def block(self, document, header):
        rows = re.findall(r"^" + re.escape(header) + r"\n([\s\S]*?)^}\n?", document, re.MULTILINE)
        self.assertEqual(1, len(rows), header)
        return rows[0]

    def assert_wiring(self, document):
        expected = {
            "trackedWrapperFixture": 'rootProject.layout.projectDirectory.file("' + SOURCE_PATH + '")',
            "trackedWrapperFixtureSha256": '"' + PIN + '"',
            "trackedWrapperFixtureBytes": "47505",
            "wrapperFixtureResource": '"' + RESOURCE_PATH + '"',
        }
        for name, value in expected.items():
            self.assertEqual([value], re.findall(r"^val " + name + r" = ([^\n]+)$", document, re.MULTILINE))
        resources = self.block(document, 'sourceSets.named("test") {')
        self.assertEqual(['    resources.exclude(wrapperFixtureResource)'],
                         [line for line in resources.splitlines() if line.strip() and not line.strip().startswith("//")])
        task = self.block(document, 'tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processTestResources") {')
        self.assertEqual([
            '    duplicatesStrategy = DuplicatesStrategy.FAIL',
            '    inputs.file(trackedWrapperFixture).withPropertyName("trackedWrapperFixture")',
            '        .withPathSensitivity(PathSensitivity.RELATIVE)',
            '    inputs.property("trackedWrapperFixtureSha256", trackedWrapperFixtureSha256)',
            '    inputs.property("trackedWrapperFixtureBytes", trackedWrapperFixtureBytes)',
            '    inputs.property("wrapperFixtureResource", wrapperFixtureResource)',
            '    from(trackedWrapperFixture) {',
            '        into("fixtures")',
            '        rename { "gradle-wrapper-7a9ce74c.jar" }',
            '    }',
            '    doFirst { requirePinnedWrapperFixture(trackedWrapperFixture.asFile) }',
            '    doLast { requirePinnedWrapperFixture(destinationDir.resolve(wrapperFixtureResource)) }',
        ], [line for line in task.splitlines() if line.strip() and not line.strip().startswith("//")])
        verifier = self.block(document, 'fun requirePinnedWrapperFixture(file: java.io.File) {')
        self.assertIn('require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))', verifier)
        self.assertIn('require(file.length() == trackedWrapperFixtureBytes.toLong())', verifier)
        self.assertIn('val bytes = file.inputStream().use { it.readNBytes(trackedWrapperFixtureBytes + 1) }', verifier)
        self.assertIn('require(bytes.size == trackedWrapperFixtureBytes)', verifier)
        self.assertIn('MessageDigest.getInstance("SHA-256").digest(bytes)', verifier)
        self.assertIn("(it.toInt() and 0xff).toString(16).padStart(2, '0')", verifier)
        self.assertIn('require(observed == trackedWrapperFixtureSha256)', verifier)
        self.assertNotRegex(document, r'\b(?:exec|javaexec|delete|commandLine|onlyIf|includeTestsMatching|excludeTags)\s*[({]')
        self.assertNotRegex(document, r'https?://|gradleProperty\(|environmentVariable\(')
        self.assertNotIn('src/test/resources/fixtures/gradle-wrapper-7a9ce74c.jar', document)

    def synthetic_projection(self, document, available):
        """Owned tokens stand for files; this is not a CopySpec/Gradle evaluator."""
        self.assert_wiring(document)
        source = re.search(r'^val trackedWrapperFixture = rootProject.layout.projectDirectory.file\("([^"\n]+)"\)$',
                           document, re.MULTILINE)[1]
        excluded = re.search(r'^val wrapperFixtureResource = "([^"\n]+)"$', document, re.MULTILINE)[1]
        self.assertIn(source, available, "No fallback to the old module-local duplicate")
        result = {path.removeprefix(RESOURCE_ROOT): value for path, value in available.items()
                  if path.startswith(RESOURCE_ROOT) and path.removeprefix(RESOURCE_ROOT) != excluded}
        self.assertNotIn(excluded, result)
        result[excluded] = available[source]
        return result

    def reject_mutations(self, mutations):
        original = self.document()
        self.assert_wiring(original)
        for old, new in mutations:
            with self.subTest(mutation=old):
                self.assertEqual(1, original.count(old), old)
                changed = original.replace(old, new, 1)
                self.assertNotEqual(original, changed)
                with self.assertRaises(AssertionError):
                    self.assert_wiring(changed)

    def test_exact_tracked_source_pin_bounded_verification_and_cache_inputs(self):
        self.assert_wiring(self.document())

    def test_clean_checkout_and_owned_duplicate_both_select_only_tracked_wrapper(self):
        tracked, duplicate, unrelated = object(), object(), object()
        for has_duplicate in (False, True):
            with self.subTest(module_duplicate_present=has_duplicate):
                available = {SOURCE_PATH: tracked, RESOURCE_ROOT + "catalog-control.json": unrelated}
                if has_duplicate:
                    available[RESOURCE_ROOT + RESOURCE_PATH] = duplicate
                before = dict(available)
                result = self.synthetic_projection(self.document(), available)
                self.assertEqual({RESOURCE_PATH: tracked, "catalog-control.json": unrelated}, result)
                self.assertEqual(before, available, "Excluded owned duplicate must not be deleted or rewritten")

    def test_missing_tracked_wrapper_never_falls_back_to_module_duplicate(self):
        with self.assertRaises(AssertionError):
            self.synthetic_projection(self.document(), {RESOURCE_ROOT + RESOURCE_PATH: object()})

    def test_source_pin_size_and_cache_input_mutations_are_rejected(self):
        self.reject_mutations((
            ('file("' + SOURCE_PATH + '")', 'file("modules/provider-build-contracts/src/test/resources/' + RESOURCE_PATH + '")'),
            ('val trackedWrapperFixtureSha256 = "' + PIN + '"', 'val trackedWrapperFixtureSha256 = "' + "0" * 64 + '"'),
            ('val trackedWrapperFixtureBytes = 47505', 'val trackedWrapperFixtureBytes = 47506'),
            ('inputs.file(trackedWrapperFixture).withPropertyName("trackedWrapperFixture")', ''),
            ('.withPathSensitivity(PathSensitivity.RELATIVE)', '.withPathSensitivity(PathSensitivity.NONE)'),
            ('inputs.property("trackedWrapperFixtureSha256", trackedWrapperFixtureSha256)', ''),
            ('inputs.property("trackedWrapperFixtureBytes", trackedWrapperFixtureBytes)', ''),
            ('inputs.property("wrapperFixtureResource", wrapperFixtureResource)', ''),
        ))

    def test_copy_exclusion_and_checksum_mutations_are_rejected(self):
        self.reject_mutations((
            ('resources.exclude(wrapperFixtureResource)', 'resources.exclude("fixtures/**")'),
            ('duplicatesStrategy = DuplicatesStrategy.FAIL', 'duplicatesStrategy = DuplicatesStrategy.EXCLUDE'),
            ('from(trackedWrapperFixture)', 'from(file("downloaded-wrapper.jar"))'),
            ('rename { "gradle-wrapper-7a9ce74c.jar" }', 'rename { "wrong-wrapper.jar" }'),
            ('doFirst { requirePinnedWrapperFixture(trackedWrapperFixture.asFile) }', ''),
            ('doLast { requirePinnedWrapperFixture(destinationDir.resolve(wrapperFixtureResource)) }', ''),
            ('Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)', 'file.exists()'),
            ('require(file.length() == trackedWrapperFixtureBytes.toLong())', 'require(file.length() >= 0)'),
            ('it.readNBytes(trackedWrapperFixtureBytes + 1)', 'it.readAllBytes()'),
            ('require(bytes.size == trackedWrapperFixtureBytes)', 'require(bytes.isNotEmpty())'),
            ('MessageDigest.getInstance("SHA-256").digest(bytes)', 'MessageDigest.getInstance("MD5").digest(bytes)'),
            ('require(observed == trackedWrapperFixtureSha256)', 'require(observed.isNotEmpty())'),
        ))

    def test_test_filter_process_download_and_delete_injection_is_rejected(self):
        self.reject_mutations((
            ('dependencies {', 'exec { commandLine("gradle") }\ndependencies {'),
            ('dependencies {', 'delete("src/test/resources/fixtures")\ndependencies {'),
            ('dependencies {', 'val extra = uri("https://example.invalid/wrapper.jar")\ndependencies {'),
            ('dependencies {', 'tasks.withType<Test> { filter { includeTestsMatching("*OneTest") } }\ndependencies {'),
        ))


if __name__ == "__main__":
    unittest.main()
